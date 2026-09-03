package it.uniroma2.dicii.isp2.milestone3;

import org.apache.commons.math3.distribution.TDistribution;
import org.apache.commons.math3.stat.correlation.SpearmansCorrelation;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;

import java.io.*;
import java.util.Locale;
import java.util.Properties;


public class CorrelationTableGenerator {

    private static String FULL_DATASET;
    private static String OUTPUT_CSV;

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }

            FULL_DATASET = configProps.getProperty("weka.dataset.csv");
            OUTPUT_CSV = configProps.getProperty("milestone3.correlation.csv");

            if (FULL_DATASET == null || OUTPUT_CSV == null) {
                throw new RuntimeException("CRITICAL ERROR: Missing parameters in config.properties.");
            }

        } catch (IOException e) {
            throw new RuntimeException("Unable to load config.properties.", e);
        }
    }

    private static final String SMELL_COLUMN_NAME = "NSmells";
    private static final String BUGGY_COLUMN_NAME = "Bugginess";
    private static final String RELEASE_COLUMN_NAME = "Release_ID";

    private static final double SIGNIFICANCE_LEVEL = 0.05;

    // Set true if want to include Release_ID
    private static final boolean INCLUDE_RELEASE_ID = false;

    public static void main(String[] args) throws Exception {

        Locale.setDefault(Locale.US);

        System.out.println("===================================================================");
        System.out.println("=== GENERATING MILESTONE 3 CORRELATION TABLE ===");
        System.out.println("===================================================================");

        Instances datasetA = loadDataset(FULL_DATASET);

        int smellIndex = findAttributeIndex(datasetA, SMELL_COLUMN_NAME);
        int buggyIndex = findAttributeIndex(datasetA, BUGGY_COLUMN_NAME, "Defectiveness");

        if (smellIndex < 0 || buggyIndex < 0) {
            throw new IllegalArgumentException("Columns NSmells or Bugginess not found.");
        }

        double[] smellArrayA = extractNumericValues(datasetA, smellIndex);
        double[] bugginessArrayA = extractBugginessValues(datasetA, buggyIndex);

        SpearmansCorrelation spearman = new SpearmansCorrelation();

        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_CSV))) {
            writer.println("Variable,Mean A,Mean B,Mean C," + "Correlation (rho) NSmells," + "Correlation (rho) Defectiveness");
            printConsoleHeader();

            for (int attributeIndex = 0; attributeIndex < datasetA.numAttributes(); attributeIndex++) {
                Attribute attribute = datasetA.attribute(attributeIndex);
                if (!attribute.isNumeric() || attributeIndex == buggyIndex) {
                    continue;
                }

                String variableName = cleanName(attribute.name());

                // Release_ID is an identifier and not a predictive software metric. It can optionally be included.
                if (!INCLUDE_RELEASE_ID && variableName.equalsIgnoreCase(RELEASE_COLUMN_NAME)) {
                    continue;
                }

                double[] variableArrayA = extractNumericValues(datasetA, attributeIndex);
                Means means = calculateMeans(datasetA, attributeIndex, smellIndex);

                // Dataset B contains the same instances as B+, namely those originally having NSmells > 0.
                // All feature values remain unchanged, except NSmells, which is counterfactually set to zero.
                double meanB = attributeIndex == smellIndex ? 0.0 : means.meanBPlus;

                String correlationWithSmells;

                if (attributeIndex == smellIndex) {
                    correlationWithSmells = "-";
                } else {
                    correlationWithSmells = formatCorrelation(spearman.correlation(variableArrayA, smellArrayA), variableArrayA.length);
                }

                String correlationWithBugginess = formatCorrelation(spearman.correlation(variableArrayA, bugginessArrayA), variableArrayA.length);

                System.out.printf(Locale.US, "%-30s | %10.2f | %10.2f | %10.2f" + " | %15s | %20s%n", variableName, means.meanA, meanB, means.meanC, correlationWithSmells, correlationWithBugginess);
                writer.printf(Locale.US, "%s,%.2f,%.2f,%.2f,%s,%s%n", variableName, means.meanA, meanB, means.meanC, correlationWithSmells, correlationWithBugginess);
            }
        }

        System.out.println();
        System.out.println("File saved successfully in: " + OUTPUT_CSV);
        System.out.println("The asterisk indicates an estimated p-value < 0.05.");
    }

    private static Instances loadDataset(String path) throws Exception {

        String header;
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            header = reader.readLine();
        }

        if (header == null) {
            throw new IllegalArgumentException("The input dataset is empty.");
        }

        String delimiter = header.contains(";") ? ";" : ",";

        CSVLoader loader = new CSVLoader();
        loader.setFieldSeparator(delimiter);
        loader.setSource(new File(path));

        Instances dataset = loader.getDataSet();

        if (dataset.numInstances() == 0) {
            throw new IllegalArgumentException("The input dataset contains no instances.");
        }

        return dataset;
    }

    private static int findAttributeIndex(Instances dataset, String... acceptedNames) {

        for (int i = 0; i < dataset.numAttributes(); i++) {
            String currentName = cleanName(dataset.attribute(i).name());
            for (String acceptedName : acceptedNames) {
                if (currentName.equalsIgnoreCase(acceptedName)) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String cleanName(String name) {
        return name.replace("\"", "").trim();
    }

    private static double[] extractNumericValues(Instances dataset, int attributeIndex) {

        double[] values = new double[dataset.numInstances()];
        for (int i = 0; i < dataset.numInstances(); i++) {
            Instance instance = dataset.instance(i);

            if (instance.isMissing(attributeIndex)) {
                throw new IllegalArgumentException("Missing value found for attribute " + dataset.attribute(attributeIndex).name() + " at instance " + i);
            }

            values[i] = instance.value(attributeIndex);
        }
        return values;
    }

    private static double[] extractBugginessValues(Instances dataset, int buggyIndex) {

        double[] values = new double[dataset.numInstances()];
        for (int i = 0; i < dataset.numInstances(); i++) {
            Instance instance = dataset.instance(i);

            if (instance.isMissing(buggyIndex)) {
                throw new IllegalArgumentException("Missing Bugginess value at instance " + i);
            }

            String value = instance.stringValue(buggyIndex);

            if (value.equalsIgnoreCase("YES")) {
                values[i] = 1.0;
            } else if (value.equalsIgnoreCase("NO")) {
                values[i] = 0.0;
            } else {
                throw new IllegalArgumentException("Unexpected Bugginess value: " + value);
            }
        }
        return values;
    }

    private static Means calculateMeans(Instances dataset, int attributeIndex, int smellIndex) {

        double sumA = 0.0;
        double sumBPlus = 0.0;
        double sumC = 0.0;

        int countA = 0;
        int countBPlus = 0;
        int countC = 0;

        for (int i = 0; i < dataset.numInstances(); i++) {
            Instance instance = dataset.instance(i);
            if (instance.isMissing(attributeIndex) || instance.isMissing(smellIndex)) {
                continue;
            }

            double value = instance.value(attributeIndex);
            double smellValue = instance.value(smellIndex);

            sumA += value;
            countA++;

            if (smellValue > 0.0) {
                sumBPlus += value;
                countBPlus++;
            } else {
                sumC += value;
                countC++;
            }
        }

        double meanA = countA > 0 ? sumA / countA : Double.NaN;
        double meanBPlus = countBPlus > 0 ? sumBPlus / countBPlus : Double.NaN;
        double meanC = countC > 0 ? sumC / countC : Double.NaN;
        return new Means(meanA, meanBPlus, meanC);
    }

    private static String formatCorrelation(double correlation, int observations) {

        if (Double.isNaN(correlation) || Double.isInfinite(correlation)) {
            return "-";
        }

        double pValue = calculatePValue(correlation, observations);
        String significanceMarker = pValue < SIGNIFICANCE_LEVEL ? "*" : "";

        return String.format(Locale.US, "%.2f%s", correlation, significanceMarker);
    }

    // Approximate two-tailed p-value based on the Student t transformation.
    private static double calculatePValue(double correlation, int observations) {

        if (observations <= 2 || Double.isNaN(correlation)) {
            return 1.0;
        }

        if (Math.abs(correlation) >= 1.0) {
            return 0.0;
        }

        double numerator = observations - 2.0;
        double denominator = 1.0 - correlation * correlation;
        double tStatistic = correlation * Math.sqrt(numerator / denominator);

        TDistribution distribution = new TDistribution(observations - 2.0);

        return 2.0 * (1.0 - distribution.cumulativeProbability(Math.abs(tStatistic)));
    }

    private static void printConsoleHeader() {

        System.out.printf("%-30s | %10s | %10s | %10s" + " | %15s | %20s%n", "Variable", "Mean A", "Mean B", "Mean C", "Corr NSmells", "Corr Defectiveness");
        System.out.println("------------------------------------------------" + "----------------------------------------------" + "--------------------------------");
    }

    private static class Means {

        private final double meanA;
        private final double meanBPlus;
        private final double meanC;

        private Means(double meanA, double meanBPlus, double meanC) {

            this.meanA = meanA;
            this.meanBPlus = meanBPlus;
            this.meanC = meanC;
        }
    }
}