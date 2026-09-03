package it.uniroma2.dicii.isp2.milestone1;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

public class RetrieveTicketsID {

    private static String TICKETS_CSV;

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }

            TICKETS_CSV = configProps.getProperty("tickets.csv");
            if (TICKETS_CSV == null) {
                throw new RuntimeException("CRITICAL ERROR: Missing parameters in config.properties.");
            }

        } catch (IOException e) {
            throw new RuntimeException("Unable to load config.properties.", e);
        }
    }

    // Helper method to parse the stream
    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }

    // Reads a JSON Array (Not strictly used here, but kept for consistency)
    public static JSONArray readJsonArrayFromUrl(String url) throws IOException, JSONException {
        InputStream is = new URL(url).openStream();
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            String jsonText = readAll(rd);
            return new JSONArray(jsonText);
        } finally {
            is.close();
        }
    }

    // Reads a JSON Object (Used for Jira API)
    public static JSONObject readJsonFromUrl(String url) throws IOException, JSONException {
        InputStream is = new URL(url).openStream();
        try {
            BufferedReader rd = new BufferedReader(new InputStreamReader(is, Charset.forName("UTF-8")));
            String jsonText = readAll(rd);
            return new JSONObject(jsonText);
        } finally {
            is.close();
        }
    }

    public static void main(String[] args) throws IOException, JSONException {

        String projName = "ZOOKEEPER";
        Integer j = 0, i = 0, total = 1;

        // 1. Prepare the CSV file for output
        String outname = TICKETS_CSV;
        FileWriter fileWriter = new FileWriter(outname);
        fileWriter.append("Ticket ID,Creation Date (OV),Resolution Date (FV),Affected Versions (AV)\n");

        System.out.println("Starting Bug extraction from Jira for project " + projName + "...");

        // Get JSON API for closed bugs in the project
        do {
            // Only gets a max of 1000 at a time, so must do this multiple times if bugs > 1000
            j = i + 1000;

            String url = "https://issues.apache.org/jira/rest/api/2/search?jql=project=%22" + projName + "%22AND%22issueType%22=%22Bug%22AND(%22status%22=%22closed%22OR" + "%22status%22=%22resolved%22)AND%22resolution%22=%22fixed%22&fields=key,resolutiondate,versions,created&startAt=" + i.toString() + "&maxResults=" + j.toString();

            JSONObject json = readJsonFromUrl(url);
            JSONArray issues = json.getJSONArray("issues");
            total = json.getInt("total");

            // Iterate through each bug in the current page
            for (int k = 0; k < issues.length(); k++) {
                JSONObject issue = issues.getJSONObject(k);
                JSONObject fields = issue.getJSONObject("fields");

                // Extract Ticket ID
                String key = issue.getString("key");

                // Extract Creation Date (Only YYYY-MM-DD)
                String creationDate = fields.getString("created").substring(0, 10);

                // Extract Resolution Date (Only YYYY-MM-DD)
                String resolutionDate = "N/A";
                if (fields.has("resolutiondate") && !fields.isNull("resolutiondate")) {
                    resolutionDate = fields.getString("resolutiondate").substring(0, 10);
                }

                // Extract Affected Versions (if present)
                JSONArray versionsArray = fields.getJSONArray("versions");
                List<String> affectedVersions = new ArrayList<>();
                for (int v = 0; v < versionsArray.length(); v++) {
                    affectedVersions.add(versionsArray.getJSONObject(v).getString("name"));
                }

                // Join affected versions with a pipe '|' character (e.g., "3.4.1|3.4.2")
                String affectedVersionsStr = affectedVersions.isEmpty() ? "N/A" : String.join("|", affectedVersions);

                // Write the extracted data into the CSV file
                fileWriter.append(key).append(",").append(creationDate).append(",").append(resolutionDate).append(",").append(affectedVersionsStr).append("\n");
            }

            // Increment index by the number of issues processed
            i += issues.length();
            System.out.println("Downloaded " + i + " bugs out of " + total);

        } while (i < total);

        // Close and save the file
        fileWriter.flush();
        fileWriter.close();
        System.out.println("Extraction completed! File saved as " + outname);
    }
}