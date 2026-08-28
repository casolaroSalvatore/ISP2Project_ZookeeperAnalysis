package it.uniroma2.dicii.isp2.milestone1;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;

public class GetReleaseInfo {

    public static HashMap<LocalDateTime, String> releaseNames;
    public static HashMap<LocalDateTime, String> releaseID;
    public static ArrayList<LocalDateTime> releases;
    public static Integer numVersions;

    public static void main(String[] args) throws IOException, JSONException {

        releases = new ArrayList<>();
        releaseNames = new HashMap<>();
        releaseID = new HashMap<>();

        String projNameJira = "ZOOKEEPER";
        String repoNameGitHub = "apache/zookeeper";

        // 1. Extract releases from Jira (old history)
        System.out.println("Starting Jira releases extraction for " + projNameJira + "...");
        String jiraUrl = "https://issues.apache.org/jira/rest/api/2/project/" + projNameJira;

        try {
            JSONObject jiraJson = readJsonFromUrl(jiraUrl);
            JSONArray jiraVersions = jiraJson.getJSONArray("versions");

            for (int i = 0; i < jiraVersions.length(); i++) {
                JSONObject versionObj = jiraVersions.getJSONObject(i);

                // Check if it has a date AND is marked as actually released
                if (versionObj.has("releaseDate") && versionObj.has("released") && versionObj.getBoolean("released")) {
                    String name = versionObj.optString("name", "unknown");
                    String id = versionObj.optString("id", "unknown");
                    String dateStr = versionObj.getString("releaseDate");

                    addRelease(dateStr, name, id);
                }
            }
            System.out.println("Jira extraction completed. Total unique releases so far: " + releases.size());
        } catch (Exception e) {
            System.out.println("Error extracting from Jira. Continuing with GitHub...");
            e.printStackTrace();
        }

        // 2. Extract releases from GitHub (recent history)
        System.out.println("Starting GitHub releases extraction for " + repoNameGitHub + "...");
        int page = 1;
        boolean hasMoreReleases = true;

        while (hasMoreReleases) {
            String githubUrl = "https://api.github.com/repos/" + repoNameGitHub + "/releases?per_page=100&page=" + page;
            JSONArray githubVersions = readJsonArrayFromUrl(githubUrl);

            if (githubVersions.length() == 0) {
                hasMoreReleases = false;
                break;
            }

            for (int i = 0; i < githubVersions.length(); i++) {
                JSONObject versionObj = githubVersions.getJSONObject(i);

                if (versionObj.has("published_at") && !versionObj.isNull("published_at")) {
                    String name = versionObj.optString("tag_name", "unknown");
                    String id = String.valueOf(versionObj.optInt("id"));

                    // Extract only "YYYY-MM-DD" from ISO format
                    String dateStr = versionObj.getString("published_at").substring(0, 10);

                    addRelease(dateStr, name, id);
                }
            }
            page++;
        }
        System.out.println("GitHub extraction completed. Total unique releases now: " + releases.size());

        // 3. Sort and generate .csv files

        // Order releases by date
        Collections.sort(releases, new Comparator<LocalDateTime>() {
            public int compare(LocalDateTime o1, LocalDateTime o2) {
                return o1.compareTo(o2);
            }
        });

        if (releases.size() < 6) {
            System.out.println("Found less than 6 valid releases in total. Aborting.");
            return;
        }

        // Complete Dataset generation
        try (FileWriter fileWriterFull = new FileWriter(projNameJira + "VersionInfo_Full.csv")) {
            fileWriterFull.append("Index,Version ID,Version Name,Date\n");

            for (int i = 0; i < releases.size(); i++) {
                Integer index = i + 1;
                fileWriterFull.append(index.toString()).append(",")
                        .append(releaseID.get(releases.get(i))).append(",")
                        .append(releaseNames.get(releases.get(i))).append(",")
                        .append(releases.get(i).toString()).append("\n");
            }
            System.out.println("FULL file successfully generated: " + releases.size() + " total releases.");
        } catch (Exception e) {
            System.out.println("Error while creating the complete CSV file.");
            e.printStackTrace();
        }

        // Filtered Dataset Generation (Snoring - First 34%
        int numberOfValidReleases = (int) Math.round(releases.size() * 0.34);
        numVersions = numberOfValidReleases;

        try (FileWriter fileWriterFiltered = new FileWriter(projNameJira + "VersionInfo_Filtered.csv")) {
            fileWriterFiltered.append("Index,Version ID,Version Name,Date\n");

            for (int i = 0; i < numberOfValidReleases; i++) {
                Integer index = i + 1;
                fileWriterFiltered.append(index.toString()).append(",")
                        .append(releaseID.get(releases.get(i))).append(",")
                        .append(releaseNames.get(releases.get(i))).append(",")
                        .append(releases.get(i).toString()).append("\n");
            }
            System.out.println("FILTERED file successfully generated: " + numberOfValidReleases + " valid releases for training.");
        } catch (Exception e) {
            System.out.println("Error while creating the filtered CSV file.");
            e.printStackTrace();
        }
    }

    // Adds a release to the collections. If a release on the exact same date already exists, it is skipped to avoid duplicates.
    public static void addRelease(String strDate, String name, String id) {
        LocalDate date = LocalDate.parse(strDate);
        LocalDateTime dateTime = date.atStartOfDay();

        if (!releases.contains(dateTime)) {
            releases.add(dateTime);
            releaseNames.put(dateTime, name);
            releaseID.put(dateTime, id);
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

    // Reads a JSON Array (Used for GitHub API)
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

    // Helper method to parse the stream
    private static String readAll(Reader rd) throws IOException {
        StringBuilder sb = new StringBuilder();
        int cp;
        while ((cp = rd.read()) != -1) {
            sb.append((char) cp);
        }
        return sb.toString();
    }
}