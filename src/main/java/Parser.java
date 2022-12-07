import java.io.*;
import java.util.*;
import org.javatuples.Pair;
import org.javatuples.Triplet;
import org.javatuples.Quartet;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;

class SysdigEvent {
    public long index;
    public String process;
    public String operation;
    public String fileDescriptor;
    public Time startTime;
    public Time endTime;

    public SysdigEvent(
        long index, String process, String operation, String fileDescriptor, Time startTime, Time endTime
    ) {
        this.index = index;
        this.process = process;
        this.operation = operation;
        this.fileDescriptor = fileDescriptor;
        this.startTime = startTime;
        this.endTime = endTime;
    }
}

public class Parser {
    // Creates list of 3-tuples best on raw output from sysdig
    public static ArrayList<SysdigEvent> parseTuples(String filename) {
        ArrayList<SysdigEvent> list = new ArrayList<>();
        try {
            File raw = new File(filename);
            Scanner s = new Scanner(raw);

            HashMap<Pair<String, String>, Time> startTimes = new HashMap<>();

            while (s.hasNextLine()) {
                String line = s.nextLine();
                String[] split = line.split(" ");

                String[] timeRaw = split[1].split("\\.");
                Time time = new Time(Long.valueOf(timeRaw[0]), Long.valueOf(timeRaw[1]));
                String proc = split[2];
                String op = split[4];
                String fd = split[5];
                Pair<String, String> pair = new Pair<>(proc, op);

                if (split[3].equals(">")) {
                    startTimes.put(pair, time);
                } else if (split[3].equals("<")) {
                    // Only capture events with unique IDs
                    if (!fd.contains("<unix>") && !fd.contains("<timerfd>") && !fd.contains("<inotify>")
                            && !fd.contains("<pipe>") && !fd.contains("<netlink>"))
                    {
                        SysdigEvent trip = new SysdigEvent(
                            Long.valueOf(split[0]),
                            proc,
                            op,
                            fd,
                            startTimes.get(pair),
                            time
                        );
                        list.add(trip);
                    }
                }
            }
            s.close();
        } catch (FileNotFoundException e) {
            System.out.println("Error");
        }

        return list;
    }

    public static Pair<DirectedMultigraph<String, TimedEdge>, HashMap<Long, TimedEdge>> createGraph(
        ArrayList<SysdigEvent> tuples
    ) {
        // Multigraph is chosen because processes may access the same file more than once
        DirectedMultigraph<String, TimedEdge> graph = new DirectedMultigraph(LabeledEdge.class);
        ArrayList<String> uniqueVertices = new ArrayList<>();
        HashMap<Long, TimedEdge> indicesToEdge = new HashMap<>();

        for (int i = 0; i < tuples.size(); ++i) {
            // Extract next tuple
            SysdigEvent event = tuples.get(i);
            String procName = event.process;
            String operation = event.operation;
            String objName = event.fileDescriptor;
            TimedEdge edge = new TimedEdge(event.index, event.startTime, event.endTime);
            indicesToEdge.put(event.index, edge);

            // Add process as vertex if necessary
            if (!uniqueVertices.contains(procName)) {
                graph.addVertex(procName);
                uniqueVertices.add(procName);
            }
            // Add object as vertex if necessary
            if (!uniqueVertices.contains(objName)) {
                graph.addVertex(objName);
                uniqueVertices.add(objName);
            }
            // Determine direction based on operation
            String from, to;

            if (isOutward(operation)) {
                from = procName;
                to = objName;
            } else {
                from = objName;
                to = procName;
            }

            // Add edge between vertices
            graph.addEdge(from, to, edge);
        }

        return new Pair<>(graph, indicesToEdge);
    }

    public static boolean isOutward(String operation) {
        if (operation.equals("sendto") || operation.equals("sendmsg") ||
                operation.equals("write") || operation.equals("writev") ||
                operation.equals("execve") || operation.equals("fcntl")) {
            return true;
        } else {
            return false;
        }
    }

    public static void outputGraph(DirectedMultigraph<String, TimedEdge> graph, Time startTime) {
        DOTExporter<String, TimedEdge> exporter = new DOTExporter<>();

        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            return map;
        });
        exporter.setEdgeAttributeProvider((e) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(e.toString(startTime)));
            return map;
        });

        File dotFile = new File("graph-output.dot");
        try {
            Writer writer = new FileWriter(dotFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        exporter.exportGraph(graph, dotFile);
    }

    public static DirectedMultigraph<String, TimedEdge> backtrack(
        DirectedMultigraph<String, TimedEdge> graph,
        TimedEdge pointOfInterest
    ) {
        DirectedMultigraph<String, TimedEdge> output = new DirectedMultigraph<>(TimedEdge.class);
        Queue<TimedEdge> frontier = new ArrayDeque<>();
        Set<TimedEdge> visited = new HashSet<>();

        output.addVertex(pointOfInterest.source());
        output.addVertex(pointOfInterest.target());
        output.addEdge(pointOfInterest.source(), pointOfInterest.target(), pointOfInterest);
        frontier.add(pointOfInterest);

        while (frontier.size() > 0) {
            TimedEdge edge = frontier.remove();

            Time maxEndtime = new Time(0);
            for (TimedEdge incomingEdge: graph.edgesOf(edge.source())) {
                maxEndtime = Time.max(incomingEdge.endTime, maxEndtime);
            }

            // look at the source incoming edges, to see if we can traverse through them
            for (TimedEdge incomingEdge: graph.edgesOf(edge.source())) {
                if (
                    edge.index == incomingEdge.index
                    || !incomingEdge.target().equals(edge.source())
                    || visited.contains(incomingEdge))
                {
                    continue;
                }

                if (incomingEdge.startTime.less(edge.endTime) && incomingEdge.startTime.less(maxEndtime)) {
                    output.addVertex(incomingEdge.source());
                    output.addVertex(incomingEdge.target());
                    output.addEdge(incomingEdge.source(), incomingEdge.target(), incomingEdge);
                    frontier.add(incomingEdge);
                    visited.add(incomingEdge);
                }
            }
        }

        return output;
    }

    public static void outputBacktrackedGraph(DirectedMultigraph<String, TimedEdge> graph, Time startTime) {
        DOTExporter<String, TimedEdge> exporter = new DOTExporter<>();

        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            return map;
        });
        exporter.setEdgeAttributeProvider((e) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(e.toString(startTime)));
            return map;
        });

        File dotFile = new File("backtrack-graph-output.dot");
        try {
            Writer writer = new FileWriter(dotFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        exporter.exportGraph(graph, dotFile);
    }

    public static void main(String[] args) {
        ArrayList<SysdigEvent> tuples = parseTuples(args[0]);

        ArrayList<Long> indices = new ArrayList<>();
        for (SysdigEvent event: tuples) {
            indices.add(event.index);
        }

        Time startTime = tuples.get(0).startTime;
        Pair<DirectedMultigraph<String, TimedEdge>, HashMap<Long, TimedEdge>> result = createGraph(tuples);
        DirectedMultigraph<String, TimedEdge> fullGraph = result.getValue0();

        HashMap<Long, TimedEdge> indicesToEdge = result.getValue1();
        TimedEdge pointOfInterest = indicesToEdge.get(Long.valueOf(17858));

        // {
        //     DirectedMultigraph<String, TimedEdge> test = new DirectedMultigraph(TimedEdge.class);
        //     test.addVertex("malware");
        //     test.addVertex("wget");
        //     test.addVertex("files 1");
        //     test.addVertex("files 2");
        //     test.addVertex("bash");
        //     test.addVertex("httpd");

        //     TimedEdge testPointOfInterest = test.addEdge("wget", "malware");
        //     testPointOfInterest.startTime = new Time(36);
        //     testPointOfInterest.endTime = new Time(37);

        //     test.addEdge("wget", "files 1", new TimedEdge(1, new Time(50), new Time(52)));
        //     test.addEdge("bash", "wget", new TimedEdge(2, new Time(28), new Time(32)));
        //     test.addEdge("files 2", "bash", new TimedEdge(3, new Time(40), new Time(42)));
        //     test.addEdge("httpd", "bash", new TimedEdge(4, new Time(2), new Time(8)));

        //     outputBacktrackedGraph(backtrack(test, testPointOfInterest), new Time(0));
        // }

        int biggestSize = 0;
        long biggestIndex = 0;
        for (long index: indices) {
            var size = backtrack(fullGraph, indicesToEdge.get(index)).vertexSet().size();
            if (size > biggestSize) {
                biggestIndex = index;
                biggestSize = size;
            }
        }

        // pointOfInterest = indicesToEdge.get(Long.valueOf(biggestIndex));

        if (pointOfInterest != null) {
            outputBacktrackedGraph(backtrack(fullGraph, pointOfInterest), startTime);
        } else {
            System.out.println("point of interest was not found");
        }

        /*
        // Demo for Question 1
        for (Triplet<String, String, String> tuple : tuples) {
            System.out.println(tuple);
        }
        */

        // Demo for Question 2
        outputGraph(fullGraph, startTime);
    }
}

class LabeledEdge extends DefaultEdge {
    private String label;

    public LabeledEdge() {

    }

    public LabeledEdge(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return "(" + getSource() + " : " + getTarget() + " : " + label + ")";
    }
}

class Time {
    public long seconds = 0;
    public long nanoseconds = 0;

    public Time(long seconds) {
        this.seconds = seconds;
        this.nanoseconds = 0;
    }

    public Time(long seconds, long nanoseconds) {
        this.seconds = seconds;
        this.nanoseconds = nanoseconds;
    }

    public boolean less(Time other) {
        if (this.seconds < other.seconds) {
            return true;
        } else if (this.seconds == other.seconds) {
            return this.nanoseconds < other.nanoseconds;
        } else {
            return false;
        }
    }

    public static Time max(Time a, Time b) {
        if (a.less(b)) {
            return b;
        } else {
            return a;
        }
    }

    public double toDouble(Time startTime) {
        long seconds = this.seconds - startTime.seconds;
        long nanoseconds = this.nanoseconds - startTime.nanoseconds;

        if (nanoseconds < 0) {
            nanoseconds = 1_000_000_000 - Math.abs(nanoseconds);
            seconds -= 1;
        }

        return Math.floor((double)seconds + (double)nanoseconds / 1_000_000_000.0 * 100) / 100;
    }

    @Override
    public String toString() {
        return seconds + "." + nanoseconds;
    }

    public String toString(Time startTime) {
        return String.valueOf(toDouble(startTime));
    }
}

class TimedEdge extends DefaultEdge {
    public Time startTime;
    public Time endTime;
    public long index;

    public TimedEdge() {

    }

    public TimedEdge(long index, Time startTime, Time endTime) {
        this.index = index;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public TimedEdge(long index, TimedEdge edge) {
        this.index = index;
        this.startTime = edge.startTime;
        this.endTime = edge.endTime;
    }

    @Override
    public String toString() {
        return "[" + startTime + ", " + endTime + "]";
    }

    public String toString(Time time) {
        return "[" + index + ", " + startTime.toString(time) + ".." + endTime.toString(time) + "]";
    }

    public String source() {
        return (String)this.getSource();
    }

    public String target() {
        return (String)this.getTarget();
    }
}
