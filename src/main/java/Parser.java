import java.io.*;
import java.util.*;
import org.javatuples.Triplet;
import org.jgrapht.graph.DefaultWeightedEdge;
import org.jgrapht.graph.DirectedWeightedMultigraph;
import org.jgrapht.nio.Attribute;
import org.jgrapht.nio.DefaultAttribute;
import org.jgrapht.nio.dot.DOTExporter;


public class Parser {
    // Creates list of 3-tuples best on raw output from sysdig
    public static ArrayList<Triplet<String, String, String>> parseTuples() {
        ArrayList<Triplet<String, String, String>> list = new ArrayList<>();
        try {
            // TODO: Remove hardcoded filename and accept name from args
            File raw = new File("/home/user/raw_output.txt");
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

    public static DirectedWeightedMultigraph createGraph(ArrayList<Triplet<String, String, String>> tuples) {
        // Multigraph is chosen because processes may access the same file more than once
        // and weighted edges are used for labelling the order in events occur
        DirectedWeightedMultigraph graph = new DirectedWeightedMultigraph(DefaultWeightedEdge.class);
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
            // Add edge between vertices
            // TODO: Determine direction based on operation
            DefaultWeightedEdge newEdge = (DefaultWeightedEdge) graph.addEdge(procName, objName);
            // Edge "weight" is the order it appears
            graph.setEdgeWeight(newEdge, i);
        }

        return graph;
    }

    public static void main(String[] args) {
        // TODO: Parse arguments
        ArrayList<Triplet<String, String, String>> tuples = parseTuples();

        /*
        // Demo for Question 1
        for (Triplet<String, String, String> tuple : tuples) {
            System.out.println(tuple);
        }
        */

        // Demo for Question 2
        DirectedWeightedMultigraph fullGraph = createGraph(tuples);
        DOTExporter<String,DefaultWeightedEdge> exporter = new DOTExporter<>();
        exporter.setVertexAttributeProvider((v) -> {
            Map<String, Attribute> map = new LinkedHashMap<>();
            map.put("label", DefaultAttribute.createAttribute(v.toString()));
            return map;
        });

        File dotFile = new File("graph-output.dot");
        try {
            Writer writer = new FileWriter(dotFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        exporter.exportGraph(fullGraph, dotFile);

    }
}
