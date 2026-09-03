package it.uniroma2.dicii.isp2.milestone4;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class SonarCSVExporter {

    private static String SONAR_TOKEN;
    private static String SONAR_PROJECT_KEY;
    private static String SONAR_URL;
    private static String OUTPUT_CSV;

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }

            SONAR_TOKEN = System.getenv("SONAR_TOKEN");
            SONAR_PROJECT_KEY = configProps.getProperty("sonar.project.key.testing");
            SONAR_URL = configProps.getProperty("sonar.url");
            OUTPUT_CSV = configProps.getProperty("milestone4.classselection.csv");

            if (SONAR_TOKEN == null || SONAR_TOKEN.isBlank() || SONAR_PROJECT_KEY == null || SONAR_URL == null || OUTPUT_CSV == null) {
                throw new RuntimeException("CRITICAL ERROR: Missing configuration.");
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to load configuration.", e);
        }
    }

    public static void main(String[] args) throws IOException {
        System.out.println("Exporting metrics...");

        List<ClassMetrics> metricsList = fetchMetrics();

        // SORTING:
        // 1. By NSmells descending (from largest to smallest)
        // 2. In case of ties, by LOC descending (from largest to smallest)
        metricsList.sort(Comparator.comparingInt((ClassMetrics c) -> c.smells).reversed().thenComparing(Comparator.comparingInt((ClassMetrics c) -> c.loc).reversed()));

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_CSV))) {
            writer.write("Class Name,LOC,NSmells\n");

            for (ClassMetrics c : metricsList) {
                writer.write(String.format("%s,%d,%d\n", c.className, c.loc, c.smells));
            }
        }
        System.out.println("CSV file generated and correctly sorted at: " + OUTPUT_CSV);
    }

    private static List<ClassMetrics> fetchMetrics() throws IOException {
        List<ClassMetrics> list = new ArrayList<>();
        int page = 1;
        int pageSize = 500;
        boolean hasMore = true;

        String authString = SONAR_TOKEN + ":";
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        while (hasMore) {
            String urlStr = String.format("%s/api/measures/component_tree?component=%s&metricKeys=ncloc,code_smells&qualifiers=FIL&p=%d&ps=%d", SONAR_URL, SONAR_PROJECT_KEY, page, pageSize);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

            if (conn.getResponseCode() != 200) {
                throw new RuntimeException("SonarQube error: " + conn.getResponseCode());
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = in.readLine()) != null) {
                response.append(line);
            }

            in.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray components = json.optJSONArray("components");

            if (components == null || components.length() == 0) {
                hasMore = false;
            } else {
                for (int i = 0; i < components.length(); i++) {
                    JSONObject comp = components.getJSONObject(i);
                    String path = comp.getString("path");

                    int loc = 0;
                    int smells = 0;

                    JSONArray measures = comp.optJSONArray("measures");

                    if (measures != null) {
                        for (int j = 0; j < measures.length(); j++) {
                            JSONObject m = measures.getJSONObject(j);

                            if (m.getString("metric").equals("ncloc")) {
                                loc = Integer.parseInt(m.getString("value"));
                            }

                            if (m.getString("metric").equals("code_smells")) {
                                smells = Integer.parseInt(m.getString("value"));
                            }
                        }
                    }

                    // Automatic filter: Exclude test files, files with LOC < 150, AND files with NO smells
                    if (loc >= 150 && !path.contains("Test") && smells > 0) {
                        list.add(new ClassMetrics(path, loc, smells));
                    }
                }

                JSONObject paging = json.optJSONObject("paging");
                hasMore = (paging != null && (page * pageSize < paging.getInt("total")));
                page++;
            }
        }

        return list;
    }

    // Support class used to manage data before writing it
    static class ClassMetrics {
        String className;
        int loc;
        int smells;

        public ClassMetrics(String className, int loc, int smells) {
            this.className = className;
            this.loc = loc;
            this.smells = smells;
        }
    }
}