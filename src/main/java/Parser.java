import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;
import org.javatuples.Triplet;
import org.jgrapht.*;
import java.util.ArrayList;

public class Parser {
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

    public static void main(String[] args) {
        // TODO: Parse arguments
        ArrayList<Triplet<String, String, String>> tuples = parseTuples();

    }
}
