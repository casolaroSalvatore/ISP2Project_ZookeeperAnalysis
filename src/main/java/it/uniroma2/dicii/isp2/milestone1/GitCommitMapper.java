package it.uniroma2.dicii.isp2.milestone1;

import java.io.*;

public class GitCommitMapper {

    public static void main(String[] args) {

        // Insert the path of the cloned directory
        String repoPath = "C:\\Users\\casol\\Desktop\\zookeeper";

        // Input & Output file
        String inputCsv = "ZOOKEEPERVersionInfo_Filtered.csv";
        String outputCsv = "ZOOKEEPER_Mapped_Filtered.csv";

        try {
            processCsvAndMapCommits(inputCsv, outputCsv, repoPath);
            System.out.println("Mappatura Git completata con successo!");
        } catch (Exception e) {
            System.out.println("Errore durante la mappatura: " + e.getMessage());
            e.printStackTrace();
        }
    }

    public static void processCsvAndMapCommits(String inputFilePath, String outputFilePath, String repoDir) throws IOException {
        BufferedReader reader = new BufferedReader(new FileReader(inputFilePath));
        BufferedWriter writer = new BufferedWriter(new FileWriter(outputFilePath));

        String line = reader.readLine();
        if (line != null) {
            writer.write(line + ",Commit Hash\n");
        }

        while ((line = reader.readLine()) != null) {
            String[] parts = line.split(",");
            if (parts.length >= 4) {
                String dateStr = parts[3];

                // Extract the hash of the commit invoking Git
                String commitHash = getCommitHashBeforeDate(dateStr, repoDir);

                // Write the new line in the output CSV file
                writer.write(line + "," + commitHash + "\n");
                System.out.println("Release " + parts[2] + " -> Commit: " + commitHash);
            }
        }

        reader.close();
        writer.close();
    }

    public static String getCommitHashBeforeDate(String date, String repoDir) {
        String commitHash = "NOT_FOUND";
        try {
            // Prepare the Git command
            ProcessBuilder builder = new ProcessBuilder(
                    "git", "log", "--before=" + date, "-n", "1", "--format=%H"
            );
            // Set the directory on which run the command
            builder.directory(new File(repoDir));

            Process process = builder.start();

            BufferedReader stdInput = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String output = stdInput.readLine();

            if (output != null && !output.trim().isEmpty()) {
                commitHash = output.trim();
            }

            // Wait for the termination of Git command
            process.waitFor();

        } catch (Exception e) {
            System.err.println("Errore nell'esecuzione del comando Git per la data " + date);
        }
        return commitHash;
    }
}