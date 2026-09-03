package it.uniroma2.dicii.isp2.milestone1;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FeatureExtractor {

    // Path Configuration
    private static String REPO_PATH;
    private static String MAPPED_RELEASES_CSV;
    private static String BUGGINESS_CSV;
    private static String OUTPUT_CSV;

    // SonarQube Configuration
    private static String SONAR_TOKEN;
    private static String SONAR_PROJECT_KEY;
    private static String SONAR_URL;

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }

            REPO_PATH = configProps.getProperty("repo.path");
            MAPPED_RELEASES_CSV = configProps.getProperty("mapped.releases.csv");
            BUGGINESS_CSV = configProps.getProperty("bugginess.csv");
            OUTPUT_CSV = configProps.getProperty("output.csv");
            SONAR_PROJECT_KEY = configProps.getProperty("sonar.project.key");
            SONAR_URL = configProps.getProperty("sonar.url");

            SONAR_TOKEN = System.getenv("SONAR_TOKEN");

            if (SONAR_TOKEN == null) {
                Properties envProps = new Properties();
                try (InputStream envInput = new FileInputStream(".env")) {
                    envProps.load(envInput);
                    SONAR_TOKEN = envProps.getProperty("SONAR_TOKEN");
                } catch (FileNotFoundException e) {
                    System.err.println("File .env non trovato e SONAR_TOKEN non presente nelle variabili d'ambiente.");
                }
            }

            if (SONAR_TOKEN == null || SONAR_TOKEN.trim().isEmpty()) {
                throw new RuntimeException("CRITICAL ERROR: SONAR_TOKEN mancante!");
            }
            if (REPO_PATH == null || REPO_PATH.trim().isEmpty()) {
                throw new RuntimeException("CRITICAL ERROR: Configurazioni mancanti in config.properties!");
            }

        } catch (IOException e) {
            throw new RuntimeException("Errore fatale durante l'avvio: Impossibile caricare i file di configurazione.", e);
        }
    }

    // Data Structures
    static class Release {
        int index;
        String name;
        String commitHash;
        LocalDate date;
    }

    static class ProductMetrics {
        int loc;
        int nSmells;
        int nMethods;
    }

    static class FileHistory {
        int nr = 0;
        Set<String> authors = new HashSet<>();
        int locAdded = 0;
        int locDeleted = 0;
        int nFix = 0;
        LocalDate creationDate = null;
    }

    static class ReleaseMetrics {
        int nr = 0;
        Set<String> authors = new HashSet<>();
        List<Integer> locAddedList = new ArrayList<>();
        List<Integer> locDeletedList = new ArrayList<>();
        List<Integer> ndList = new ArrayList<>();
        List<Double> entropyList = new ArrayList<>();
        List<Integer> expList = new ArrayList<>();
        int nFix = 0;
        int nMulti = 0;
        int sumMultiChgSet = 0;
        int maxChgSet = 0;
    }

    public static void main(String[] args) {
        System.out.println("Loading metadata...");

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(OUTPUT_CSV))) {

            List<Release> releases = loadReleases(MAPPED_RELEASES_CSV);
            Map<String, String> bugginessMap = loadBugginess(BUGGINESS_CSV);

            Map<String, FileHistory> globalHistory = new HashMap<>();
            Map<String, Integer> authorExperience = new HashMap<>();

            // Scrittura dell'Header ESATTAMENTE nell'ordine richiesto (più gli identificatori e la Label)
            writer.write("Project_Name,Class_Name,Release_ID,Size_LOC,N_Methods," +
                    "NR_Release,NR_Total,Nfix_Release,Nfix_Total,Nauth_Release,Nauth_Total," +
                    "MAX_LOC_Added_Release,Churn_Release,Churn_Total,AVG_Churn_Release," +
                    "MAX_ChgSet_Release,N_Multi_Release,Sum_Multi_ChgSet_Release," +
                    "Age_Weeks,Weighted_Age,ND,Entropy,Exp,Historical_Bug_Density,NSmells,Bugginess\n");
            writer.flush();

            String lastCommitHash = null;

            for (Release release : releases) {
                System.out.println("\n--- Processing Release " + release.name + " (Index: " + release.index + ") ---");

                System.out.println("Checking out commit " + release.commitHash + "...");
                executeCommand(REPO_PATH, "git", "checkout", "-f", release.commitHash);

                System.out.println("Running SonarScanner...");
                executeCommand(REPO_PATH, "sonar-scanner.bat",
                        "-Dsonar.projectKey=" + SONAR_PROJECT_KEY,
                        "-Dsonar.sources=.",
                        "-Dsonar.host.url=" + SONAR_URL,
                        "-Dsonar.login=" + SONAR_TOKEN,
                        "-Dsonar.java.binaries=.");

                System.out.println("Waiting for SonarQube background processing task to complete...");
                waitForSonarJob(SONAR_PROJECT_KEY);

                System.out.println("Fetching LOC, NSmells, and Methods from SonarQube API...");
                Map<String, ProductMetrics> sonarMetrics = fetchSonarMetrics();

                if (sonarMetrics.isEmpty()) {
                    System.err.println("WARNING: No metrics returned from SonarQube. Check Token and Server Status.");
                }

                System.out.println("Computing process metrics (Git history)...");
                Map<String, ReleaseMetrics> releaseMetrics = new HashMap<>();
                extractGitMetrics(REPO_PATH, lastCommitHash, release.commitHash, release.date,
                        globalHistory, releaseMetrics, authorExperience);

                System.out.println("Assembling dataset rows and labeling...");
                for (Map.Entry<String, ProductMetrics> entry : sonarMetrics.entrySet()) {
                    String filePath = entry.getKey();

                    if (filePath.toLowerCase().contains("/test/") || filePath.endsWith("Test.java")) {
                        continue;
                    }

                    ProductMetrics prod = entry.getValue();
                    FileHistory st = globalHistory.getOrDefault(filePath, new FileHistory());
                    ReleaseMetrics rm = releaseMetrics.getOrDefault(filePath, new ReleaseMetrics());

                    // Calcoli base per Churn
                    int locAddedRel = rm.locAddedList.stream().mapToInt(Integer::intValue).sum();
                    int locDeletedRel = rm.locDeletedList.stream().mapToInt(Integer::intValue).sum();
                    int churnRel = locAddedRel + locDeletedRel;
                    double avgChurn = rm.nr > 0 ? (double) churnRel / rm.nr : 0.0;

                    // MAX_LOC_Added_Release
                    int maxLocAdded = rm.locAddedList.stream().mapToInt(Integer::intValue).max().orElse(0);

                    // Commit Metrics Calculations
                    double avgNd = rm.nr > 0 ? rm.ndList.stream().mapToInt(Integer::intValue).sum() / (double) rm.nr : 0.0;
                    double avgExp = rm.nr > 0 ? rm.expList.stream().mapToInt(Integer::intValue).sum() / (double) rm.nr : 0.0;
                    double avgEntropy = rm.nr > 0 ? rm.entropyList.stream().mapToDouble(Double::doubleValue).sum() / rm.nr : 0.0;

                    // Età e Età Ponderata
                    long ageWeeks = 0;
                    if (st.creationDate != null) {
                        ageWeeks = ChronoUnit.WEEKS.between(st.creationDate, release.date);
                        if (ageWeeks < 0) ageWeeks = 0;
                    }
                    int locTouchedRel = locAddedRel + locDeletedRel;
                    double weightedAge = (double) ageWeeks * locTouchedRel;

                    // Densità Storica
                    double historicalBugDensity = st.nr > 0 ? (double) st.nFix / st.nr : 0.0;

                    // Labeling
                    String bugKey = release.index + "_" + filePath;
                    String bugginess = bugginessMap.getOrDefault(bugKey, "NO");

                    // Scrittura riga
                    writer.write(String.format(Locale.US, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%.2f,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.3f,%d,%s\n",
                            "ZOOKEEPER", filePath, release.index,
                            prod.loc, prod.nMethods,                 // Size_LOC, N_Methods
                            rm.nr, st.nr,                            // NR_Release, NR_Total
                            rm.nFix, st.nFix,                        // Nfix_Release, Nfix_Total
                            rm.authors.size(), st.authors.size(),    // Nauth_Release, Nauth_Total
                            maxLocAdded,                             // MAX_LOC_Added_Release
                            churnRel, (st.locAdded + st.locDeleted), avgChurn, // Churn (Rel, Tot, Avg)
                            rm.maxChgSet, rm.nMulti, rm.sumMultiChgSet, // ChangeSet (Max, Multi_N, Multi_Sum)
                            ageWeeks, weightedAge,                   // Age
                            avgNd, avgEntropy, avgExp,               // Commit Metrics
                            historicalBugDensity,                    // Bug Density
                            prod.nSmells, bugginess));               // Smells & Label
                }

                writer.flush();
                lastCommitHash = release.commitHash;
            }

            System.out.println("\nRestoring Git repository to master branch...");
            executeCommand(REPO_PATH, "git", "checkout", "-f", "master");

            System.out.println("DONE! Dataset saved perfectly in " + OUTPUT_CSV);

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void waitForSonarJob(String projectKey) throws InterruptedException {
        String urlStr = String.format("%s/api/ce/component?component=%s", SONAR_URL, projectKey);
        int errorCount = 0;
        int checkCount = 0;

        String authString = SONAR_TOKEN + ":";
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        while (true) {
            checkCount++;
            if (errorCount >= 3 || checkCount > 24) {
                System.out.println(" Warning: SonarQube API polling unreachable. Applying a 40-second safe sleep instead...");
                Thread.sleep(40000);
                break;
            }

            try {
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    errorCount++;
                } else {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String inputLine;
                    while ((inputLine = in.readLine()) != null) {
                        response.append(inputLine);
                    }
                    in.close();

                    JSONObject json = new JSONObject(response.toString());
                    JSONArray queue = json.optJSONArray("queue");
                    boolean isRunning = false;

                    if (queue != null && queue.length() > 0) {
                        isRunning = true;
                    }

                    if (json.has("current")) {
                        String status = json.getJSONObject("current").getString("status");
                        if (status.equals("PENDING") || status.equals("IN_PROGRESS")) {
                            isRunning = true;
                        }
                    }

                    if (!isRunning) {
                        System.out.println("SonarQube background task processed successfully!");
                        break;
                    }
                }
            } catch (Exception e) {
                errorCount++;
            }
            Thread.sleep(5000);
        }
    }

    private static Map<String, ProductMetrics> fetchSonarMetrics() throws IOException {
        Map<String, ProductMetrics> map = new HashMap<>();
        int page = 1;
        int pageSize = 500;
        boolean hasMore = true;

        String authString = SONAR_TOKEN + ":";
        String encodedAuth = Base64.getEncoder().encodeToString(authString.getBytes(StandardCharsets.UTF_8));

        while (hasMore) {
            String urlStr = String.format("%s/api/measures/component_tree?component=%s&metricKeys=ncloc,code_smells,functions&qualifiers=FIL&p=%d&ps=%d",
                    SONAR_URL, SONAR_PROJECT_KEY, page, pageSize);

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

            if (conn.getResponseCode() != 200) {
                break;
            }

            BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            StringBuilder response = new StringBuilder();
            String inputLine;
            while ((inputLine = in.readLine()) != null) {
                response.append(inputLine);
            }
            in.close();

            JSONObject json = new JSONObject(response.toString());
            JSONArray components = json.optJSONArray("components");

            if (components == null || components.length() == 0) {
                hasMore = false;
                continue;
            }

            for (int i = 0; i < components.length(); i++) {
                JSONObject comp = components.getJSONObject(i);
                String path = comp.getString("path");

                ProductMetrics pm = new ProductMetrics();
                JSONArray measures = comp.optJSONArray("measures");
                if (measures != null) {
                    for (int j = 0; j < measures.length(); j++) {
                        JSONObject measure = measures.getJSONObject(j);
                        if (measure.getString("metric").equals("ncloc")) {
                            pm.loc = Integer.parseInt(measure.getString("value"));
                        } else if (measure.getString("metric").equals("code_smells")) {
                            pm.nSmells = Integer.parseInt(measure.getString("value"));
                        } else if (measure.getString("metric").equals("functions")) {
                            pm.nMethods = Integer.parseInt(measure.getString("value"));
                        }
                    }
                }
                map.put(path, pm);
            }

            JSONObject paging = json.optJSONObject("paging");
            if (paging != null) {
                int total = paging.getInt("total");
                if (page * pageSize >= total) {
                    hasMore = false;
                } else {
                    page++;
                }
            } else {
                hasMore = false;
            }
        }
        return map;
    }

    private static void extractGitMetrics(String repoPath, String lastHash, String currentHash, LocalDate releaseDate,
                                          Map<String, FileHistory> globalHistory,
                                          Map<String, ReleaseMetrics> releaseMetrics,
                                          Map<String, Integer> authorExperience) throws IOException, InterruptedException {

        List<String> command = new ArrayList<>(Arrays.asList("git", "log", "--reverse", "--numstat", "-M", "--format=COMMIT_START|%ce|%s|%cs"));
        if (lastHash != null) {
            command.add(lastHash + ".." + currentHash);
        } else {
            command.add(currentHash);
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(repoPath));
        Process process = pb.start();

        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        String line;

        String currentAuthor = "unknown";
        LocalDate commitDate = releaseDate;
        boolean isFix = false;
        List<String[]> currentCommitMods = new ArrayList<>();

        while ((line = reader.readLine()) != null) {
            if (line.startsWith("COMMIT_START|")) {
                processCommitModifications(currentCommitMods, currentAuthor, isFix, commitDate,
                        globalHistory, releaseMetrics, authorExperience);

                String[] parts = line.split("\\|", 4);
                if (parts.length > 1) currentAuthor = parts[1];
                if (parts.length > 2) {
                    String msg = parts[2].toLowerCase();
                    isFix = msg.contains("fix") || msg.contains("zookeeper-");
                }
                if (parts.length > 3) {
                    try {
                        commitDate = LocalDate.parse(parts[3].substring(0, 10));
                    } catch (Exception e) {
                        commitDate = releaseDate;
                    }
                }
                currentCommitMods.clear();
            } else if (!line.trim().isEmpty()) {
                String[] parts = line.split("\\s+", 3);
                if (parts.length >= 3) {
                    currentCommitMods.add(parts);
                }
            }
        }
        processCommitModifications(currentCommitMods, currentAuthor, isFix, commitDate,
                globalHistory, releaseMetrics, authorExperience);
        process.waitFor();
    }

    private static void processCommitModifications(List<String[]> mods, String author, boolean isFix, LocalDate commitDate,
                                                   Map<String, FileHistory> globalHistory,
                                                   Map<String, ReleaseMetrics> releaseMetrics,
                                                   Map<String, Integer> authorExperience) {
        if (mods.isEmpty()) return;

        int currentExp = authorExperience.getOrDefault(author, 0);
        authorExperience.put(author, currentExp + 1);

        Set<String> directories = new HashSet<>();
        int totalLocModified = 0;

        // Mappa per salvare in un solo colpo: Path -> [Added, Deleted]
        Map<String, int[]> fileModData = new HashMap<>();

        int currentChgSetSize = mods.size();
        boolean isMultiCommit = currentChgSetSize > 1;

        // Primo e unico clico sui mods: calcoliamo tutto qui senza ripetizioni
        for (String[] mod : mods) {
            String addedStr = mod[0];
            String deletedStr = mod[1];
            String filePathRaw = mod[2].replace("\\", "/");

            String filePath = handleGitRenaming(filePathRaw, globalHistory, releaseMetrics);

            if (!filePath.endsWith(".java")) continue;

            int added = addedStr.equals("-") ? 0 : Integer.parseInt(addedStr);
            int deleted = deletedStr.equals("-") ? 0 : Integer.parseInt(deletedStr);
            int modCount = added + deleted;

            totalLocModified += modCount;
            fileModData.put(filePath, new int[]{added, deleted});

            int lastSlash = filePath.lastIndexOf('/');
            if (lastSlash != -1) {
                directories.add(filePath.substring(0, lastSlash));
            } else {
                directories.add("/");
            }
        }

        int nd = directories.size();

        double entropy = 0.0;
        if (totalLocModified > 0) {
            // Iteriamo sui valori del nuovo fileModData per l'entropia
            for (int[] modData : fileModData.values()) {
                int modCount = modData[0] + modData[1];
                if (modCount > 0) {
                    double probability = (double) modCount / totalLocModified;
                    entropy -= probability * (Math.log(probability) / Math.log(2));
                }
            }
        }

        // Secondo ciclo (sui file Java reali): aggiorniamo le metriche usando i dati già pronti
        for (Map.Entry<String, int[]> entry : fileModData.entrySet()) {
            String filePath = entry.getKey();
            int added = entry.getValue()[0];
            int deleted = entry.getValue()[1];

            FileHistory fh = globalHistory.computeIfAbsent(filePath, k -> new FileHistory());
            if (fh.creationDate == null || commitDate.isBefore(fh.creationDate)) {
                fh.creationDate = commitDate;
            }
            fh.nr++;
            fh.authors.add(author);
            fh.locAdded += added;
            fh.locDeleted += deleted;
            if (isFix) fh.nFix++;

            ReleaseMetrics rm = releaseMetrics.computeIfAbsent(filePath, k -> new ReleaseMetrics());
            rm.nr++;
            rm.authors.add(author);
            rm.locAddedList.add(added);
            rm.locDeletedList.add(deleted);

            if (isMultiCommit) {
                rm.nMulti++;
                rm.sumMultiChgSet += currentChgSetSize;
            }
            if (currentChgSetSize > rm.maxChgSet) {
                rm.maxChgSet = currentChgSetSize;
            }

            rm.ndList.add(nd);
            rm.entropyList.add(entropy);
            rm.expList.add(currentExp);

            if (isFix) rm.nFix++;
        }
    }

    private static String handleGitRenaming(String rawPath, Map<String, FileHistory> globalHistory, Map<String, ReleaseMetrics> releaseMetrics) {
        if (!rawPath.contains("=>")) return rawPath;

        String oldPath, newPath;
        if (!rawPath.contains("{")) {
            String[] parts = rawPath.split("=>");
            oldPath = parts[0].trim();
            newPath = parts[1].trim();
        } else {
            Pattern pattern = Pattern.compile("(.*)\\{([^=]+)\\s*=>\\s*([^}]+)\\}(.*)");
            Matcher matcher = pattern.matcher(rawPath);
            if (matcher.find()) {
                String prefix = matcher.group(1);
                String oldMid = matcher.group(2).trim();
                String newMid = matcher.group(3).trim();
                String suffix = matcher.group(4);
                oldPath = (prefix + oldMid + suffix).replace("//", "/");
                newPath = (prefix + newMid + suffix).replace("//", "/");
            } else {
                return rawPath;
            }
        }

        // Sposta la storia globale
        if (globalHistory.containsKey(oldPath)) {
            globalHistory.put(newPath, globalHistory.remove(oldPath));
        }
        // Sposta anche le metriche della release corrente
        if (releaseMetrics.containsKey(oldPath)) {
            releaseMetrics.put(newPath, releaseMetrics.remove(oldPath));
        }

        return newPath;
    }

    private static List<Release> loadReleases(String path) throws IOException {
        List<Release> list = new ArrayList<>();
        BufferedReader br = new BufferedReader(new FileReader(path));
        br.readLine();
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");
            if (p.length >= 5) {
                Release r = new Release();
                r.index = Integer.parseInt(p[0]);
                r.name = p[2];
                r.date = LocalDate.parse(p[3].substring(0, 10));
                r.commitHash = p[4];
                list.add(r);
            }
        }
        br.close();
        list.sort(Comparator.comparing(r -> r.date));
        return list;
    }

    private static Map<String, String> loadBugginess(String path) throws IOException {
        Map<String, String> map = new HashMap<>();
        BufferedReader br = new BufferedReader(new FileReader(path));
        br.readLine();
        String line;
        while ((line = br.readLine()) != null) {
            String[] p = line.split(",");
            if (p.length >= 7) {
                map.put(p[0] + "_" + p[1], p[6]);
            }
        }
        br.close();
        return map;
    }

    private static void executeCommand(String directory, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(new File(directory));
        pb.inheritIO();
        Process p = pb.start();
        p.waitFor();
    }
}