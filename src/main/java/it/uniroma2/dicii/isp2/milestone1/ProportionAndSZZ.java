package it.uniroma2.dicii.isp2.milestone1;

import java.io.*;
import java.time.LocalDate;
import java.util.*;

public class ProportionAndSZZ {

    private static String RELEASES_CSV;
    private static String TICKETS_CSV;
    private static String MAPPING_CSV;
    private static String BUGGINESS_CSV;

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }
            RELEASES_CSV = configProps.getProperty("releases.full.csv");
            TICKETS_CSV = configProps.getProperty("tickets.csv");
            MAPPING_CSV = configProps.getProperty("ticket.commit.mapping.csv");
            BUGGINESS_CSV = configProps.getProperty("bugginess.csv");
            if (RELEASES_CSV == null || TICKETS_CSV == null || MAPPING_CSV == null || BUGGINESS_CSV == null) {
                throw new RuntimeException("CRITICAL ERROR: Missing parameters in config.properties.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load config.properties.", e);
        }
    }

    // Data structure for releases
    static class Release {
        int index;
        String name;
        LocalDate date;

        public Release(int i, String n, LocalDate d) {
            index = i;
            name = n;
            date = d;
        }
    }

    // Data structure for tickets
    static class Ticket {
        String id;
        LocalDate ovDate;
        LocalDate fvDate;
        List<String> affectedVersions;

        int ovIndex = -1;
        int fvIndex = -1;
        int ivIndex = -1; // Real or estimated IV
        boolean hasRealIV = false;
        double pValue = -1.0;
        List<String> modifiedFiles = new ArrayList<>();
    }

    public static void main(String[] args) {
        String releaseCsv = RELEASES_CSV;
        String ticketsCsv = TICKETS_CSV;
        String mappingCsv = MAPPING_CSV;
        String outputCsv = BUGGINESS_CSV;

        try {
            // Data loading
            List<Release> releases = loadReleases(releaseCsv);
            Map<String, Ticket> tickets = loadTickets(ticketsCsv);
            linkFilesToTickets(mappingCsv, tickets);

            // Temporal mapping (dates -> indices)
            mapDatesToIndices(tickets, releases);

            // Proportion calculation (moving window)
            calculateProportion(tickets);

            // Labeling (bugginess)
            generateBugginessFile(tickets, outputCsv);

            System.out.println("Phase 4 completed successfully! Results saved in " + outputCsv);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static List<Release> loadReleases(String path) throws IOException {
        List<Release> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line = br.readLine();

        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");
            list.add(new Release(Integer.parseInt(p[0]), p[2], LocalDate.parse(p[3].substring(0, 10))));
        }

        br.close();

        // Sort by date
        list.sort(Comparator.comparing(r -> r.date));

        return list;
    }

    private static Map<String, Ticket> loadTickets(String path) throws IOException {
        Map<String, Ticket> map = new HashMap<>();
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line = br.readLine();

        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");

            if (p.length < 4) continue;

            Ticket t = new Ticket();
            t.id = p[0];
            t.ovDate = LocalDate.parse(p[1]);
            t.fvDate = p[2].equals("N/A") ? null : LocalDate.parse(p[2]);
            t.affectedVersions = p[3].equals("N/A") ? new ArrayList<>() : Arrays.asList(p[3].split("\\|"));

            map.put(t.id, t);
        }

        br.close();
        return map;
    }

    private static void linkFilesToTickets(String path, Map<String, Ticket> tickets) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(path));
        String line = br.readLine();

        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");

            if (p.length >= 3 && tickets.containsKey(p[0])) {
                tickets.get(p[0]).modifiedFiles.addAll(Arrays.asList(p[2].split("\\|")));
            }
        }

        br.close();
    }

    private static void mapDatesToIndices(Map<String, Ticket> tickets, List<Release> releases) {
        for (Ticket t : tickets.values()) {

            // Ignore tickets without a fix date
            if (t.fvDate == null) continue;

            t.ovIndex = getReleaseIndexAfter(t.ovDate, releases);
            t.fvIndex = getReleaseIndexAfter(t.fvDate, releases);

            // Consistency rule: if FV == OV, force FV = OV + 1
            if (t.fvIndex <= t.ovIndex) {
                t.fvIndex = t.ovIndex + 1;
            }

            // Compute IV from affected versions (if available)
            if (!t.affectedVersions.isEmpty()) {
                int oldestAvIndex = Integer.MAX_VALUE;

                for (String av : t.affectedVersions) {
                    int idx = getReleaseIndexByName(av, releases);

                    if (idx != -1 && idx < oldestAvIndex) oldestAvIndex = idx;
                }

                if (oldestAvIndex != Integer.MAX_VALUE && oldestAvIndex <= t.ovIndex) {
                    t.ivIndex = oldestAvIndex;
                    t.hasRealIV = true;

                    // Compute P for this ticket: P = (FV - IV) / (FV - OV)
                    t.pValue = (double) (t.fvIndex - t.ivIndex) / (t.fvIndex - t.ovIndex);
                }
            }
        }
    }

    private static void calculateProportion(Map<String, Ticket> tickets) {

        // Separate tickets into two lists
        List<Ticket> withIV = new ArrayList<>();
        List<Ticket> withoutIV = new ArrayList<>();

        for (Ticket t : tickets.values()) {

            // Filter out unmapped tickets or tickets without files
            if (t.fvIndex == -1 || t.modifiedFiles.isEmpty()) continue;

            if (t.hasRealIV) withIV.add(t);
            else withoutIV.add(t);
        }

        // Sort tickets with IV by fix date for the moving window
        withIV.sort(Comparator.comparing(t -> t.fvDate));

        // Moving window: compute average P on 1% of past tickets (minimum 5 tickets)
        int windowSize = Math.max(5, (int) Math.round(withIV.size() * 0.01));

        for (Ticket t : withoutIV) {
            double pAvg = getMovingWindowP(t.fvDate, withIV, windowSize);

            // IV = FV - (FV - OV) * P
            int estimatedIV = (int) Math.round(t.fvIndex - (t.fvIndex - t.ovIndex) * pAvg);

            // Safety check: IV cannot be greater than OV
            if (estimatedIV > t.ovIndex) estimatedIV = t.ovIndex;

            if (estimatedIV < 1) estimatedIV = 1;

            t.ivIndex = estimatedIV;
        }
    }

    private static double getMovingWindowP(LocalDate currentDate, List<Ticket> withIV, int windowSize) {
        double sumP = 0;
        int count = 0;

        // Traverse the list backwards to select the most recent previous tickets
        for (int i = withIV.size() - 1; i >= 0; i--) {
            Ticket pastTicket = withIV.get(i);

            if (pastTicket.fvDate.isBefore(currentDate) || pastTicket.fvDate.isEqual(currentDate)) {
                sumP += pastTicket.pValue;
                count++;

                if (count == windowSize) break;
            }
        }

        // If there are no previous tickets (beginning of the project), use P = 1 as fallback
        return count == 0 ? 1.0 : sumP / count;
    }

    private static void generateBugginessFile(Map<String, Ticket> tickets, String outputCsv) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputCsv));
        writer.write("Release Index,File Name,Bug Ticket,IV,OV,FV,Bugginess\n");

        for (Ticket t : tickets.values()) {

            // Ignore incorrectly mapped tickets or tickets without modified files
            if (t.ivIndex == -1 || t.fvIndex == -1 || t.modifiedFiles.isEmpty()) continue;

            // SZZ rule: the file is buggy from release IV (included) to FV (excluded)
            for (String file : t.modifiedFiles) {
                for (int releaseIdx = t.ivIndex; releaseIdx < t.fvIndex; releaseIdx++) {

                    writer.write(releaseIdx + "," + file + "," + t.id + "," + t.ivIndex + "," + t.ovIndex + "," + t.fvIndex + ",YES\n");
                }
            }
        }

        writer.close();
    }

    // Helper methods
    private static int getReleaseIndexAfter(LocalDate date, List<Release> releases) {
        for (Release r : releases) {
            if (r.date.isAfter(date) || r.date.isEqual(date)) return r.index;
        }

        // Fallback to the last release
        return releases.get(releases.size() - 1).index;
    }

    private static int getReleaseIndexByName(String name, List<Release> releases) {
        for (Release r : releases) {
            if (r.name.equalsIgnoreCase(name)) return r.index;
        }

        return -1;
    }
}