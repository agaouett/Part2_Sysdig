import java.io.*;
import java.util.*;
import org.javatuples.Triplet;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.DirectedMultigraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;


public class Parser {
    // Creates list of 3-tuples best on raw output from sysdig
    public static ArrayList<Triplet<String, String, String>> parseTuples(String filename) {
        ArrayList<Triplet<String, String, String>> list = new ArrayList<>();
        try {
            File raw = new File(filename);
            Scanner s = new Scanner(raw);

            while (s.hasNextLine()) {
                String line = s.nextLine();
                String[] split = line.split(" ");
                // Skip exits ("<")
                if (!split[3].equals("<")) {
                    String proc = split[2];
                    String op = split[4];
                    String fd = split[5];
                    // Only capture events with unique IDs
                    if (!fd.contains("<unix>") && !fd.contains("<timerfd>") && !fd.contains("<inotify>")
                            && !fd.contains("<pipe>") && !fd.contains("<netlink>")) {
                        Triplet<String, String, String> trip = new Triplet<>(proc, op, fd);
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

    public static DirectedMultigraph<String, String> createGraph(ArrayList<Triplet<String, String, String>> tuples) {
        // Multigraph is chosen because processes may access the same file more than once
        DirectedMultigraph<String, String> graph = new DirectedMultigraph(LabeledEdge.class);
        ArrayList<String> uniqueVertices = new ArrayList<>();

        for (int i = 0; i < tuples.size(); ++i) {
            // Extract next tuple
            Triplet<String, String, String> tuple = tuples.get(i);
            String procName = tuple.getValue0();
            String operation = tuple.getValue1();
            String objName = tuple.getValue2();

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
            graph.addEdge(from, to, Integer.toString(i));
        }

        return graph;
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

    public static void outputGraph(DirectedMultigraph<String, String> graph) {
        DOTExporter<String, String> exporter = new DOTExporter<>();

        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            return map;
        });
        exporter.setEdgeAttributeProvider((e) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(e.toString()));
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
        DirectedMultigraph<String, TimedEdge> output = new DirectedMultigraph(TimedEdge.class);
        Queue<TimedEdge> frontier = new ArrayDeque();
        Set<TimedEdge> visited = new HashSet();

        output.addVertex(pointOfInterest.source());
        output.addVertex(pointOfInterest.target());
        output.addEdge(pointOfInterest.source(), pointOfInterest.target(), new TimedEdge(pointOfInterest));
        frontier.add(pointOfInterest);

        while (frontier.size() > 0) {
            TimedEdge edge = frontier.remove();

            Time maxEndtime = new Time(0);
            for (TimedEdge incomingEdge: graph.edgesOf(edge.source())) {
                maxEndtime = Time.max(incomingEdge.endTime, maxEndtime);
            }

            // look at the source incoming edges, to see if we can traverse through them
            for (TimedEdge incomingEdge: graph.edgesOf(edge.source())) {
                if (edge == incomingEdge || incomingEdge.target() != edge.source() || visited.contains(incomingEdge)) {
                    continue;
                }

                if (incomingEdge.startTime.less(edge.endTime) && incomingEdge.startTime.less(maxEndtime)) {
                    output.addVertex(incomingEdge.source());
                    output.addVertex(incomingEdge.target());
                    output.addEdge(incomingEdge.source(), incomingEdge.target(), new TimedEdge(incomingEdge));
                    frontier.add(incomingEdge);
                    visited.add(incomingEdge);
                }
            }
        }

        return output;
    }

    public static void outputBacktrackedGraph(DirectedMultigraph<String, TimedEdge> graph) {
        DOTExporter<String, TimedEdge> exporter = new DOTExporter<>();

        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            return map;
        });
        exporter.setEdgeAttributeProvider((e) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(e.toString()));
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
        ArrayList<Triplet<String, String, String>> tuples = parseTuples(args[0]);
        DirectedMultigraph fullGraph = createGraph(tuples);

        DirectedMultigraph<String, TimedEdge> test = new DirectedMultigraph(TimedEdge.class);
        test.addVertex("malware");
        test.addVertex("wget");
        test.addVertex("files 1");
        test.addVertex("files 2");
        test.addVertex("bash");
        test.addVertex("httpd");

        TimedEdge pointOfInterest = test.addEdge("wget", "malware");
        pointOfInterest.startTime = new Time(36);
        pointOfInterest.endTime = new Time(37);

        test.addEdge("wget", "files 1", new TimedEdge(new Time(50), new Time(52)));
        test.addEdge("bash", "wget", new TimedEdge(new Time(28), new Time(32)));
        test.addEdge("files 2", "bash", new TimedEdge(new Time(40), new Time(42)));
        test.addEdge("httpd", "bash", new TimedEdge(new Time(2), new Time(8)));

        outputBacktrackedGraph(backtrack(test, pointOfInterest));

        /*
        // Demo for Question 1
        for (Triplet<String, String, String> tuple : tuples) {
            System.out.println(tuple);
        }
        */

        // Demo for Question 2
        // outputGraph(fullGraph);
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
}

class TimedEdge extends DefaultEdge {
    public Time startTime;
    public Time endTime;

    public TimedEdge() {

    }

    public TimedEdge(Time startTime, Time endTime) {
        this.startTime = startTime;
        this.endTime = endTime;
    }

    public TimedEdge(TimedEdge edge) {
        this.startTime = edge.startTime;
        this.endTime = edge.endTime;
    }

    @Override
    public String toString() {
        return "[" + startTime + ", " + endTime + "]";
    }

    public String source() {
        return (String)this.getSource();
    }

    public String target() {
        return (String)this.getTarget();
    }
}
