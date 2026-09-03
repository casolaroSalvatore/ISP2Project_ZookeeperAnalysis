package it.uniroma2.dicii.isp2.milestone1;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LinkTicketsToCommits {

    private static String REPO_PATH;
    private static String INPUT_TICKETS_CSV;
    private static String OUTPUT_MAPPING_CSV;

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }
            REPO_PATH = configProps.getProperty("repo.path");
            INPUT_TICKETS_CSV = configProps.getProperty("tickets.csv");
            OUTPUT_MAPPING_CSV = configProps.getProperty("ticket.commit.mapping.csv");
            if (REPO_PATH == null || INPUT_TICKETS_CSV == null || OUTPUT_MAPPING_CSV == null) {
                throw new RuntimeException("CRITICAL ERROR: Missing parameters in config.properties.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load config.properties.", e);
        }
    }

    public static void main(String[] args) {

        try {
            // Load all Ticket IDs from Phase 2 into memory (e.g., ZOOKEEPER-123)
            Set<String> validBugs = loadValidBugs(INPUT_TICKETS_CSV);
            System.out.println("Loaded " + validBugs.size() + " valid bugs from the CSV file.");

            // Parse the Git log and perform the matching
            System.out.println("Scanning Git log (this may take a few minutes)...");
            extractFixCommits(REPO_PATH, validBugs, OUTPUT_MAPPING_CSV);

        } catch (Exception e) {
            System.err.println("Error during linking: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static Set<String> loadValidBugs(String csvPath) throws IOException {
        Set<String> bugs = new HashSet<>();
        BufferedReader br = new BufferedReader(new FileReader(csvPath));
        // Skip the header
        String line = br.readLine();

        while ((line = br.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length > 0) {
                // Extract the first column (Ticket ID)
                bugs.add(parts[0].trim());
            }
        }

        br.close();
        return bugs;
    }

    private static void extractFixCommits(String repoDir, Set<String> validBugs, String outputCsv) throws IOException {

        BufferedWriter writer = new BufferedWriter(new FileWriter(outputCsv));
        writer.write("Ticket ID,Commit Hash,Modified Java Files\n");

        // Prepare the regex pattern to identify tickets (e.g., ZOOKEEPER-123)
        Pattern ticketPattern = Pattern.compile("ZOOKEEPER-\\d+");

        // Execute the git log command. --name-only shows the modified files
        ProcessBuilder builder = new ProcessBuilder("git", "log", "--name-only");
        builder.directory(new File(repoDir));
        Process process = builder.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

        String line;
        String currentCommitHash = "";
        Set<String> currentFoundTickets = new HashSet<>();
        List<String> currentJavaFiles = new ArrayList<>();

        while ((line = reader.readLine()) != null) {

            // If the line starts with "commit", we are processing a new commit
            if (line.startsWith("commit ")) {

                // Before moving to the next one, save the completed commit data
                saveCommitData(writer, validBugs, currentCommitHash, currentFoundTickets, currentJavaFiles);

                // Reset the state for the new commit
                currentCommitHash = line.substring(7).trim(); // Extract the hash after "commit "
                currentFoundTickets.clear();
                currentJavaFiles.clear();
                continue;
            }

            // Search for tickets IDs in the commit message using Regex
            Matcher matcher = ticketPattern.matcher(line);
            while (matcher.find()) {
                // Add the detected ticket (e.g., ZOOKEEPER-1411)
                currentFoundTickets.add(matcher.group());
            }

            // Search for modified .java file (optionally ignoring test classes)
            // A file path line does not start with spaces and ends with .java
            if (line.endsWith(".java") && !line.startsWith(" ") && !line.startsWith("\t")) {

                // Optional: test files are often excluded from analysis
                if (!line.contains("/test/") && !line.contains("Test")) {
                    currentJavaFiles.add(line.trim());
                }
            }
        }

        // Save the last processed commit when the log ends
        saveCommitData(writer, validBugs, currentCommitHash, currentFoundTickets, currentJavaFiles);

        reader.close();
        writer.close();

        System.out.println("Ticket-Commit-File mapping completed! Saved to: " + outputCsv);
    }

    private static void saveCommitData(BufferedWriter writer, Set<String> validBugs, String commitHash, Set<String> foundTickets, List<String> javaFiles) throws IOException {

        // Skip if no tickets were found or no Java files were modified
        if (foundTickets.isEmpty() || javaFiles.isEmpty()) {
            return;
        }

        // A single commit may resolve multiple tickets (e.g., ZOOKEEPER-1, ZOOKEEPER-2)
        for (String ticket : foundTickets) {

            // Verify that the detected ticket is a real bug extracted in Phase 2
            if (validBugs.contains(ticket)) {

                // Format the file list using "|" as separator
                String filesStr = String.join("|", javaFiles);
                writer.write(ticket + "," + commitHash + "," + filesStr + "\n");
            }
        }
    }
}