package it.uniroma2.dicii.isp2.milestone3;

import weka.classifiers.Classifier;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;

import java.io.*;
import java.util.Locale;
import java.util.Properties;

public class WekaWhatIfAnalyzer {

    private static String FULL_DATASET;
    private static String OUTPUT_CSV;


    private static final String PROJECT_COLUMN = "Project_Name";
    private static final String CLASS_COLUMN = "Class_Name";
    private static final String RELEASE_COLUMN = "Release_ID";
    private static final String SMELL_COLUMN = "NSmells";
    private static final String POSITIVE_CLASS = "YES";

    static {
        try {
            Properties configProps = new Properties();
            try (InputStream input = new FileInputStream("config.properties")) {
                configProps.load(input);
            }

            FULL_DATASET = configProps.getProperty("weka.dataset.csv");
            OUTPUT_CSV = configProps.getProperty("milestone3.whatif.output.csv");
            if (FULL_DATASET == null || OUTPUT_CSV == null) {
                throw new RuntimeException("CRITICAL ERROR: Missing parameters in config.properties.");
            }

        } catch (IOException e) {
            throw new RuntimeException("Unable to load config.properties.", e);
        }
    }

    public static void main(String[] args) throws Exception {

        Locale.setDefault(Locale.US);

        System.out.println("====================================================");
        System.out.println("MILESTONE 3: WHAT-IF ANALYSIS");
        System.out.println("Random Forest, no Feature Selection, no SMOTE");
        System.out.println("====================================================");

        // Load Dataset A
        Instances datasetA = loadDataset(FULL_DATASET);
        validateDataset(datasetA);

        int smellIndex = datasetA.attribute(SMELL_COLUMN).index();

        // Construct C, B+ and B
        DatasetCollection datasets = createWhatIfDatasets(datasetA, smellIndex);

        System.out.println("Datasets created:");
        System.out.println(" - Dataset A:  " + datasetA.numInstances() + " instances");
        System.out.println(" - Dataset C:  " + datasets.datasetC.numInstances() + " instances with NSmells = 0");
        System.out.println(" - Dataset B+: " + datasets.datasetBPlus.numInstances() + " instances with NSmells > 0");
        System.out.println(" - Dataset B:  " + datasets.datasetB.numInstances() + " counterfactual instances");

        if (datasets.datasetBPlus.numInstances() != datasets.datasetB.numInstances()) {
            throw new IllegalStateException("B+ and B do not contain the same number of instances.");
        }

        // Remove identifiers from all datasets
        DatasetCollection withoutIdentifiers = removeIdentifiers(datasetA, datasets.datasetC, datasets.datasetBPlus, datasets.datasetB);

        // Normalize all numerical predictors
        DatasetCollection normalized = normalizeDatasets(withoutIdentifiers.datasetA, withoutIdentifiers.datasetC, withoutIdentifiers.datasetBPlus, withoutIdentifiers.datasetB);

        // Train the BClassifier on the complete Dataset A, Random Forest woth no Feature Selection, no SMOTE, normalization enabled
        Classifier bClassifierA = new RandomForest();
        bClassifierA.buildClassifier(normalized.datasetA);

        int yesIndex = getYesIndex(normalized.datasetA);


        // Obtain the aggregate results for A, C, B+ and B
        DatasetEvaluation resultsA = evaluateDataset(bClassifierA, normalized.datasetA, yesIndex);
        DatasetEvaluation resultsC = evaluateDataset(bClassifierA, normalized.datasetC, yesIndex);
        DatasetEvaluation resultsBPlus = evaluateDataset(bClassifierA, normalized.datasetBPlus, yesIndex);
        DatasetEvaluation resultsB = evaluateDataset(bClassifierA, normalized.datasetB, yesIndex);


        // Calculate the aggregate Actual-versus-Expected results for the What-If Analysis.

        long estimatedAvoided = resultsBPlus.actualBuggy - resultsB.expectedBuggy;
        long predictedDrop = resultsBPlus.expectedBuggy - resultsB.expectedBuggy;
        double reductionBPlus = percentage(estimatedAvoided, resultsBPlus.actualBuggy);
        double overallReduction = percentage(estimatedAvoided, resultsA.actualBuggy);
        double predictionReduction = percentage(predictedDrop, resultsBPlus.expectedBuggy);

        printResults(resultsA, resultsBPlus, resultsB, resultsC, estimatedAvoided, reductionBPlus, overallReduction, predictedDrop, predictionReduction);
        writeSummary(resultsA, resultsBPlus, resultsB, resultsC, estimatedAvoided, reductionBPlus, overallReduction, predictedDrop, predictionReduction);

        System.out.println();
        System.out.println("Analysis completed successfully.");
        System.out.println("Summary: " + OUTPUT_CSV);
    }


    // Dataset loading and validation
    private static Instances loadDataset(String path) throws Exception {
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String header = reader.readLine();

            if (header == null) {
                throw new IllegalArgumentException("The input dataset is empty.");
            }

            String delimiter = header.contains(";") ? ";" : ",";

            CSVLoader loader = new CSVLoader();
            loader.setSource(new File(path));
            loader.setFieldSeparator(delimiter);

            Instances data = loader.getDataSet();

            if (data.numAttributes() == 0) {
                throw new IllegalArgumentException("No attributes were loaded.");
            }

            data.setClassIndex(data.numAttributes() - 1);
            return data;
        }
    }

    private static void validateDataset(Instances data) {

        String[] requiredAttributes = {PROJECT_COLUMN, CLASS_COLUMN, RELEASE_COLUMN, SMELL_COLUMN};

        for (String attributeName : requiredAttributes) {
            if (data.attribute(attributeName) == null) {
                throw new IllegalArgumentException("Required attribute not found: " + attributeName);
            }
        }

        if (!data.classAttribute().isNominal()) {
            throw new IllegalArgumentException("The class attribute must be nominal.");
        }

        if (data.classAttribute().indexOfValue(POSITIVE_CLASS) < 0) {

            throw new IllegalArgumentException("Positive class '" + POSITIVE_CLASS + "' not found.");
        }
    }


    // Creation of A, C, B+ AND B
    private static DatasetCollection createWhatIfDatasets(Instances datasetA, int smellIndex) {

        Instances datasetC = new Instances(datasetA, 0);
        Instances datasetBPlus = new Instances(datasetA, 0);
        Instances datasetB = new Instances(datasetA, 0);

        for (int i = 0; i < datasetA.numInstances(); i++) {
            Instance originalInstance = datasetA.instance(i);
            double smellValue = originalInstance.value(smellIndex);

            if (smellValue == 0.0) {
                datasetC.add((Instance) originalInstance.copy());
            } else {
                datasetBPlus.add((Instance) originalInstance.copy());
                Instance counterfactualInstance = (Instance) originalInstance.copy();
                counterfactualInstance.setValue(smellIndex, 0.0);
                datasetB.add(counterfactualInstance);

            }
        }

        datasetC.setClassIndex(datasetA.classIndex());
        datasetBPlus.setClassIndex(datasetA.classIndex());
        datasetB.setClassIndex(datasetA.classIndex());

        return new DatasetCollection(datasetA, datasetC, datasetBPlus, datasetB);
    }


    // Pre-processing
    private static DatasetCollection removeIdentifiers(Instances datasetA, Instances datasetC, Instances datasetBPlus, Instances datasetB) throws Exception {

        int[] identifierIndices = {datasetA.attribute(PROJECT_COLUMN).index(), datasetA.attribute(CLASS_COLUMN).index(), datasetA.attribute(RELEASE_COLUMN).index()};

        Remove remove = new Remove();
        remove.setAttributeIndicesArray(identifierIndices);
        remove.setInputFormat(datasetA);

        Instances filteredA = Filter.useFilter(datasetA, remove);
        Instances filteredC = Filter.useFilter(datasetC, remove);
        Instances filteredBPlus = Filter.useFilter(datasetBPlus, remove);
        Instances filteredB = Filter.useFilter(datasetB, remove);

        setClassToLastAttribute(filteredA, filteredC, filteredBPlus, filteredB);

        return new DatasetCollection(filteredA, filteredC, filteredBPlus, filteredB);
    }

    private static DatasetCollection normalizeDatasets(Instances datasetA, Instances datasetC, Instances datasetBPlus, Instances datasetB) throws Exception {

        Normalize normalize = new Normalize();
        normalize.setIgnoreClass(true);
        normalize.setInputFormat(datasetA);

        Instances normalizedA = Filter.useFilter(datasetA, normalize);
        Instances normalizedC = Filter.useFilter(datasetC, normalize);
        Instances normalizedBPlus = Filter.useFilter(datasetBPlus, normalize);
        Instances normalizedB = Filter.useFilter(datasetB, normalize);

        setClassToLastAttribute(normalizedA, normalizedC, normalizedBPlus, normalizedB);
        validateCompatibleHeaders(normalizedA, normalizedC, "A", "C");
        validateCompatibleHeaders(normalizedA, normalizedBPlus, "A", "B+");
        validateCompatibleHeaders(normalizedA, normalizedB, "A", "B");

        return new DatasetCollection(normalizedA, normalizedC, normalizedBPlus, normalizedB);
    }

    private static void setClassToLastAttribute(Instances... datasets) {
        for (Instances dataset : datasets) {
            dataset.setClassIndex(dataset.numAttributes() - 1);
        }
    }

    private static void validateCompatibleHeaders(Instances first, Instances second, String firstName, String secondName) {
        if (!first.equalHeaders(second)) {
            throw new IllegalStateException("Incompatible headers between " + firstName + " and " + secondName + ": " + first.equalHeadersMsg(second));
        }
    }

    private static int getYesIndex(Instances dataset) {
        int yesIndex = dataset.classAttribute().indexOfValue(POSITIVE_CLASS);
        if (yesIndex < 0) {
            throw new IllegalArgumentException("Positive class '" + POSITIVE_CLASS + "' not found.");
        }
        return yesIndex;
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator == 0) {
            return Double.NaN;
        }
        return ((double) numerator / denominator) * 100.0;
    }

    // Aggregate evaluation
    private static DatasetEvaluation evaluateDataset(Classifier model, Instances dataset, int yesIndex) throws Exception {

        long actualBuggy = 0;
        long expectedBuggy = 0;

        for (int i = 0; i < dataset.numInstances(); i++) {
            Instance instance = dataset.instance(i);

            if ((int) instance.classValue() == yesIndex) {
                actualBuggy++;
            }

            double predictedClass = model.classifyInstance(instance);
            if ((int) predictedClass == yesIndex) {
                expectedBuggy++;
            }
        }

        return new DatasetEvaluation(dataset.numInstances(), actualBuggy, expectedBuggy);
    }

    // Output
    private static void printResults(DatasetEvaluation resultsA, DatasetEvaluation resultsBPlus, DatasetEvaluation resultsB, DatasetEvaluation resultsC, long estimatedAvoided, double reductionBPlus, double overallReduction, long predictedDrop, double predictionReduction) {

        System.out.println();
        System.out.println("ACTUAL AND EXPECTED RESULTS");
        System.out.println("--------------------------------------------------------------");
        System.out.printf("%-12s | %-10s | %-15s | %-15s%n", "Dataset", "Instances", "Actual buggy", "Expected buggy");
        System.out.println("--------------------------------------------------------------");

        printDatasetResult("A", resultsA, true);
        printDatasetResult("B+", resultsBPlus, true);
        printDatasetResult("B", resultsB, false);
        printDatasetResult("C", resultsC, true);

        System.out.println();
        System.out.println("AGGREGATE WHAT-IF RESULTS");
        System.out.println("--------------------------------------------------------------");
        System.out.println("Estimated avoided instances: " + estimatedAvoided);
        System.out.printf(Locale.US, "Reduction over actual buggy in B+: %.6f%%%n", reductionBPlus);
        System.out.printf(Locale.US, "Reduction over actual buggy in A: %.6f%%%n", overallReduction);
        System.out.println("Direct expected-prediction drop: " + predictedDrop);
        System.out.printf(Locale.US, "Prediction reduction over B+: %.6f%%%n", predictionReduction);
    }

    private static void printDatasetResult(String datasetName, DatasetEvaluation result, boolean actualObservable) {
        String actualValue = actualObservable ? String.valueOf(result.actualBuggy) : "N/A";
        System.out.printf(Locale.US, "%-12s | %-10d | %-15s | %-15d%n", datasetName, result.instances, actualValue, result.expectedBuggy);
    }

    private static void writeSummary(DatasetEvaluation resultsA, DatasetEvaluation resultsBPlus, DatasetEvaluation resultsB, DatasetEvaluation resultsC, long estimatedAvoided, double reductionBPlus, double overallReduction, long predictedDrop, double predictionReduction) throws Exception {

        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_CSV))) {
            writer.println("Classifier,Feature_Selection,SMOTE,Normalization");
            writer.println("RandomForest,No,No,Yes");
            writer.println();
            writer.println("Dataset,Instances,Actual_Buggy,Expected_Buggy");
            writeDatasetEvaluation(writer, "Dataset A", resultsA, true);
            writeDatasetEvaluation(writer, "Dataset B+", resultsBPlus, true);
            writeDatasetEvaluation(writer, "Dataset B", resultsB, false);
            writeDatasetEvaluation(writer, "Dataset C", resultsC, true);
            writer.println();
            writer.println("Metric,Value");
            writer.printf(Locale.US, "ESTIMATED_AVOIDED,%d%n", estimatedAvoided);
            writer.printf(Locale.US, "REDUCTION_BPLUS,%.6f%%%n", reductionBPlus);
            writer.printf(Locale.US, "OVERALL_REDUCTION,%.6f%%%n", overallReduction);
            writer.printf(Locale.US, "PREDICTED_DROP,%d%n", predictedDrop);
            writer.printf(Locale.US, "PREDICTION_REDUCTION,%.6f%%%n", predictionReduction);
        }
    }

    private static void writeDatasetEvaluation(PrintWriter writer, String datasetName, DatasetEvaluation result, boolean actualObservable) {
        String actualValue = actualObservable ? String.valueOf(result.actualBuggy) : "N/A";
        writer.printf(Locale.US, "%s,%d,%s,%d%n", datasetName, result.instances, actualValue, result.expectedBuggy);
    }


    // Data classes
    private static class DatasetCollection {

        private final Instances datasetA;
        private final Instances datasetC;
        private final Instances datasetBPlus;
        private final Instances datasetB;

        private DatasetCollection(Instances datasetA, Instances datasetC, Instances datasetBPlus, Instances datasetB) {

            this.datasetA = datasetA;
            this.datasetC = datasetC;
            this.datasetBPlus = datasetBPlus;
            this.datasetB = datasetB;
        }
    }

    private static class DatasetEvaluation {

        private final long instances;
        private final long actualBuggy;
        private final long expectedBuggy;

        private DatasetEvaluation(long instances, long actualBuggy, long expectedBuggy) {
            this.instances = instances;
            this.actualBuggy = actualBuggy;
            this.expectedBuggy = expectedBuggy;
        }
    }
}