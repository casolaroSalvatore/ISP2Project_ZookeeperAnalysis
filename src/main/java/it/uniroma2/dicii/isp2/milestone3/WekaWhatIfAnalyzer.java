package it.uniroma2.dicii.isp2.milestone3;

import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class WekaWhatIfAnalyzer {

    private static final String FULL_DATASET = "C:\\Users\\casol\\Desktop\\ISPW2\\" + "ISP2Project_ZookeeperAnalysis\\" + "Milestone1_DatasetCreation\\" + "ZOOKEEPER_Final_Dataset.csv";

    private static final String OUTPUT_CSV = "C:\\Users\\casol\\Desktop\\ISPW2\\" + "ISP2Project_ZookeeperAnalysis\\" + "Milestone3_WhatIfAnalysis\\" + "Milestone3_WhatIfAnalysis_RF_NoFS_NoSMOTE.csv";

    private static final String INSTANCE_TRANSITIONS_CSV = "C:\\Users\\casol\\Desktop\\ISPW2\\" + "ISP2Project_ZookeeperAnalysis\\" + "Milestone3_WhatIfAnalysis\\" + "Milestone3_BPlus_B_Transitions.csv";

    private static final String PROJECT_COLUMN = "Project_Name";
    private static final String CLASS_COLUMN = "Class_Name";
    private static final String RELEASE_COLUMN = "Release_ID";
    private static final String SMELL_COLUMN = "NSmells";
    private static final String POSITIVE_CLASS = "YES";

    public static void main(String[] args) throws Exception {

        Locale.setDefault(Locale.US);

        System.out.println("====================================================");
        System.out.println("MILESTONE 3: WHAT-IF ANALYSIS");
        System.out.println("Random Forest, no Feature Selection, no SMOTE");
        System.out.println("====================================================");

        /*
         * 1. Load Dataset A.
         */
        Instances datasetA = loadDataset(FULL_DATASET);
        validateDataset(datasetA);

        int smellIndex = datasetA.attribute(SMELL_COLUMN).index();

        /*
         * Preserve information used to identify each original row.
         * These attributes will not be provided to the classifier.
         */
        List<InstanceIdentifier> identifiersBPlus = new ArrayList<>();

        /*
         * 2. Construct C, B+ and B.
         */
        DatasetCollection datasets = createWhatIfDatasets(datasetA, smellIndex, identifiersBPlus);

        System.out.println("Datasets created:");
        System.out.println(" - Dataset A:  " + datasetA.numInstances() + " instances");
        System.out.println(" - Dataset C:  " + datasets.datasetC.numInstances() + " instances with NSmells = 0");
        System.out.println(" - Dataset B+: " + datasets.datasetBPlus.numInstances() + " instances with NSmells > 0");
        System.out.println(" - Dataset B:  " + datasets.datasetB.numInstances() + " counterfactual instances");

        if (datasets.datasetBPlus.numInstances() != datasets.datasetB.numInstances()) {

            throw new IllegalStateException("B+ and B do not contain the same number of instances.");
        }

        /*
         * 3. Remove identifiers from all datasets.
         *
         * The Remove filter is learned/configured from Dataset A and
         * then applied unchanged to A, C, B+ and B.
         */
        DatasetCollection withoutIdentifiers = removeIdentifiers(datasetA, datasets.datasetC, datasets.datasetBPlus, datasets.datasetB);

        /*
         * 4. Normalize all numerical predictors.
         *
         * Normalize is parameterized from Dataset A and the same
         * transformation is applied to C, B+ and B.
         */
        DatasetCollection normalized = normalizeDatasets(withoutIdentifiers.datasetA, withoutIdentifiers.datasetC, withoutIdentifiers.datasetBPlus, withoutIdentifiers.datasetB);

        /*
         * 5. Train the BClassifier on the complete Dataset A.
         *
         * Configuration selected from Milestone 2:
         * - Random Forest
         * - no Feature Selection
         * - no SMOTE
         * - normalization enabled
         */
        Classifier bClassifierA = new RandomForest();
        bClassifierA.buildClassifier(normalized.datasetA);

        int yesIndex = getYesIndex(normalized.datasetA);

        /*
         * 6. Obtain the aggregate results for A, C, B+ and B.
         */
        DatasetEvaluation resultsA = evaluateDataset(bClassifierA, normalized.datasetA, yesIndex);

        DatasetEvaluation resultsC = evaluateDataset(bClassifierA, normalized.datasetC, yesIndex);

        DatasetEvaluation resultsBPlus = evaluateDataset(bClassifierA, normalized.datasetBPlus, yesIndex);

        DatasetEvaluation resultsB = evaluateDataset(bClassifierA, normalized.datasetB, yesIndex);

        /*
         * 7. Compare B+ and B instance by instance.
         *
         * Every instance in B has the same position as its
         * corresponding original instance in B+.
         */
        TransitionResults transitions = compareBPlusAndB(bClassifierA, normalized.datasetBPlus, normalized.datasetB, identifiersBPlus, yesIndex, INSTANCE_TRANSITIONS_CSV);

        printResults(resultsA, resultsC, resultsBPlus, resultsB, transitions);

        writeSummary(resultsA, resultsC, resultsBPlus, resultsB, transitions);

        System.out.println();
        System.out.println("Analysis completed successfully.");
        System.out.println("Summary: " + OUTPUT_CSV);
        System.out.println("Transitions: " + INSTANCE_TRANSITIONS_CSV);
    }

    /*
     * ==========================================================
     * DATASET LOADING AND VALIDATION
     * ==========================================================
     */

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

    /*
     * ==========================================================
     * CREATION OF A, C, B+ AND B
     * ==========================================================
     */

    private static DatasetCollection createWhatIfDatasets(Instances datasetA, int smellIndex, List<InstanceIdentifier> identifiersBPlus) {

        Instances datasetC = new Instances(datasetA, 0);
        Instances datasetBPlus = new Instances(datasetA, 0);
        Instances datasetB = new Instances(datasetA, 0);

        int projectIndex = datasetA.attribute(PROJECT_COLUMN).index();

        int classIndex = datasetA.attribute(CLASS_COLUMN).index();

        int releaseIndex = datasetA.attribute(RELEASE_COLUMN).index();

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

                identifiersBPlus.add(new InstanceIdentifier(getStringValue(originalInstance, projectIndex), getStringValue(originalInstance, classIndex), (int) originalInstance.value(releaseIndex), smellValue));
            }
        }

        datasetC.setClassIndex(datasetA.classIndex());
        datasetBPlus.setClassIndex(datasetA.classIndex());
        datasetB.setClassIndex(datasetA.classIndex());

        return new DatasetCollection(datasetA, datasetC, datasetBPlus, datasetB);
    }

    private static String getStringValue(Instance instance, int attributeIndex) {

        if (instance.attribute(attributeIndex).isNominal() || instance.attribute(attributeIndex).isString()) {

            return instance.stringValue(attributeIndex);
        }

        return String.valueOf(instance.value(attributeIndex));
    }

    /*
     * ==========================================================
     * PRE-PROCESSING
     * ==========================================================
     */

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

    /*
     * ==========================================================
     * AGGREGATE EVALUATION
     * ==========================================================
     */

    private static DatasetEvaluation evaluateDataset(Classifier model, Instances dataset, int yesIndex) throws Exception {

        Evaluation evaluation = new Evaluation(dataset);
        evaluation.evaluateModel(model, dataset);

        long actualBuggy = Math.round(evaluation.numTruePositives(yesIndex) + evaluation.numFalseNegatives(yesIndex));

        long predictedBuggy = Math.round(evaluation.numTruePositives(yesIndex) + evaluation.numFalsePositives(yesIndex));

        long truePositives = Math.round(evaluation.numTruePositives(yesIndex));

        long falsePositives = Math.round(evaluation.numFalsePositives(yesIndex));

        long falseNegatives = Math.round(evaluation.numFalseNegatives(yesIndex));

        long trueNegatives = Math.round(evaluation.numTrueNegatives(yesIndex));

        return new DatasetEvaluation(dataset.numInstances(), actualBuggy, predictedBuggy, truePositives, falsePositives, falseNegatives, trueNegatives);
    }

    /*
     * ==========================================================
     * PAIRED B+ / B COMPARISON
     * ==========================================================
     */

    private static TransitionResults compareBPlusAndB(Classifier model, Instances datasetBPlus, Instances datasetB, List<InstanceIdentifier> identifiers, int yesIndex, String outputPath) throws Exception {

        if (datasetBPlus.numInstances() != datasetB.numInstances()) {

            throw new IllegalArgumentException("B+ and B must contain the same number of instances.");
        }

        if (datasetBPlus.numInstances() != identifiers.size()) {

            throw new IllegalArgumentException("The number of identifiers does not match B+.");
        }

        long yesToNo = 0;
        long noToYes = 0;
        long yesToYes = 0;
        long noToNo = 0;

        long preventableActualBuggy = 0;
        long actualBuggyInBPlus = 0;
        long predictedBuggyInBPlus = 0;

        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {

            writer.println("Project_Name,Class_Name,Release_ID," + "Original_NSmells,Actual_Bugginess," + "Predicted_BPlus,Probability_BPlus_YES," + "Predicted_B,Probability_B_YES,Transition," + "Preventable_Actual_Buggy");

            for (int i = 0; i < datasetBPlus.numInstances(); i++) {

                Instance original = datasetBPlus.instance(i);

                Instance counterfactual = datasetB.instance(i);

                double[] probabilityOriginal = model.distributionForInstance(original);

                double[] probabilityCounterfactual = model.distributionForInstance(counterfactual);

                int predictedOriginal = indexOfMaximum(probabilityOriginal);

                int predictedCounterfactual = indexOfMaximum(probabilityCounterfactual);

                boolean actualBuggy = (int) original.classValue() == yesIndex;

                boolean predictedOriginalBuggy = predictedOriginal == yesIndex;

                boolean predictedCounterfactualBuggy = predictedCounterfactual == yesIndex;

                if (actualBuggy) {
                    actualBuggyInBPlus++;
                }

                if (predictedOriginalBuggy) {
                    predictedBuggyInBPlus++;
                }

                String transition;

                if (predictedOriginalBuggy && !predictedCounterfactualBuggy) {

                    yesToNo++;
                    transition = "YES_TO_NO";

                } else if (!predictedOriginalBuggy && predictedCounterfactualBuggy) {

                    noToYes++;
                    transition = "NO_TO_YES";

                } else if (predictedOriginalBuggy) {

                    yesToYes++;
                    transition = "YES_TO_YES";

                } else {

                    noToNo++;
                    transition = "NO_TO_NO";
                }

                boolean preventable = actualBuggy && predictedOriginalBuggy && !predictedCounterfactualBuggy;

                if (preventable) {
                    preventableActualBuggy++;
                }

                InstanceIdentifier identifier = identifiers.get(i);

                writer.printf(Locale.US, "%s,%s,%d,%.4f,%s,%s,%.8f,%s,%.8f,%s,%s%n", escapeCsv(identifier.projectName), escapeCsv(identifier.className), identifier.releaseId, identifier.originalSmells, actualBuggy ? "YES" : "NO", predictedOriginalBuggy ? "YES" : "NO", probabilityOriginal[yesIndex], predictedCounterfactualBuggy ? "YES" : "NO", probabilityCounterfactual[yesIndex], transition, preventable ? "YES" : "NO");
            }
        }

        long netPredictedReduction = yesToNo - noToYes;

        double preventableOutOfActualBPlus = percentage(preventableActualBuggy, actualBuggyInBPlus);

        double preventableOutOfPredictedBPlus = percentage(preventableActualBuggy, predictedBuggyInBPlus);

        return new TransitionResults(yesToNo, noToYes, yesToYes, noToNo, netPredictedReduction, preventableActualBuggy, actualBuggyInBPlus, predictedBuggyInBPlus, preventableOutOfActualBPlus, preventableOutOfPredictedBPlus);
    }

    private static int indexOfMaximum(double[] values) {

        if (values == null || values.length == 0) {
            throw new IllegalArgumentException("Empty probability distribution.");
        }

        int maximumIndex = 0;

        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[maximumIndex]) {
                maximumIndex = i;
            }
        }

        return maximumIndex;
    }

    private static double percentage(long numerator, long denominator) {

        if (denominator == 0) {
            return Double.NaN;
        }

        return ((double) numerator / denominator) * 100.0;
    }

    private static int getYesIndex(Instances dataset) {

        int yesIndex = dataset.classAttribute().indexOfValue(POSITIVE_CLASS);

        if (yesIndex < 0) {
            throw new IllegalArgumentException("Positive class '" + POSITIVE_CLASS + "' not found.");
        }

        return yesIndex;
    }

    /*
     * ==========================================================
     * OUTPUT
     * ==========================================================
     */

    private static void printResults(DatasetEvaluation resultsA, DatasetEvaluation resultsC, DatasetEvaluation resultsBPlus, DatasetEvaluation resultsB, TransitionResults transitions) {

        System.out.println();
        System.out.println("AGGREGATE PREDICTION RESULTS");
        System.out.println("---------------------------------------------------------------");
        System.out.printf("%-12s | %-10s | %-13s | %-15s%n", "Dataset", "Instances", "Actual buggy", "Predicted buggy");
        System.out.println("---------------------------------------------------------------");

        printDatasetResult("A", resultsA);
        printDatasetResult("C", resultsC);
        printDatasetResult("B+", resultsBPlus);
        printDatasetResult("B", resultsB);

        System.out.println();
        System.out.println("PAIRED TRANSITIONS FROM B+ TO B");
        System.out.println("---------------------------------------------------------------");
        System.out.println("YES -> NO:  " + transitions.yesToNo);
        System.out.println("NO  -> YES: " + transitions.noToYes);
        System.out.println("YES -> YES: " + transitions.yesToYes);
        System.out.println("NO  -> NO:  " + transitions.noToNo);
        System.out.println("Net reduction in positive predictions: " + transitions.netPredictedReduction);
        System.out.println("Potentially preventable actual buggy instances: " + transitions.preventableActualBuggy);
        System.out.printf(Locale.US, "Potentially preventable / actual buggy in B+: %.4f%%%n", transitions.preventableOutOfActualBPlus);
        System.out.printf(Locale.US, "Potentially preventable / predicted buggy in B+: %.4f%%%n", transitions.preventableOutOfPredictedBPlus);
    }

    private static void printDatasetResult(String datasetName, DatasetEvaluation result) {

        System.out.printf(Locale.US, "%-12s | %-10d | %-13d | %-15d%n", datasetName, result.instances, result.actualBuggy, result.predictedBuggy);
    }

    private static void writeSummary(DatasetEvaluation resultsA, DatasetEvaluation resultsC, DatasetEvaluation resultsBPlus, DatasetEvaluation resultsB, TransitionResults transitions) throws Exception {

        try (PrintWriter writer = new PrintWriter(new FileWriter(OUTPUT_CSV))) {

            writer.println("Classifier,Feature_Selection,SMOTE,Normalization");

            writer.println("RandomForest,No,No,Yes");

            writer.println();

            writer.println("Dataset,Instances,Actual_Buggy," + "Predicted_Buggy,TP,FP,FN,TN");

            writeDatasetEvaluation(writer, "Dataset A", resultsA);
            writeDatasetEvaluation(writer, "Dataset C", resultsC);
            writeDatasetEvaluation(writer, "Dataset B+", resultsBPlus);
            writeDatasetEvaluation(writer, "Dataset B", resultsB);

            writer.println();

            writer.println("Transition,Count");

            writer.printf(Locale.US, "YES_TO_NO,%d%n", transitions.yesToNo);

            writer.printf(Locale.US, "NO_TO_YES,%d%n", transitions.noToYes);

            writer.printf(Locale.US, "YES_TO_YES,%d%n", transitions.yesToYes);

            writer.printf(Locale.US, "NO_TO_NO,%d%n", transitions.noToNo);

            writer.printf(Locale.US, "NET_POSITIVE_REDUCTION,%d%n", transitions.netPredictedReduction);

            writer.printf(Locale.US, "PREVENTABLE_ACTUAL_BUGGY,%d%n", transitions.preventableActualBuggy);

            writer.println();

            writer.println("Metric,Value");

            writer.printf(Locale.US, "Preventable_Out_Of_Actual_Buggy_BPlus,%.6f%%%n", transitions.preventableOutOfActualBPlus);

            writer.printf(Locale.US, "Preventable_Out_Of_Predicted_Buggy_BPlus,%.6f%%%n", transitions.preventableOutOfPredictedBPlus);

            writer.printf(Locale.US, "Preventable_Out_Of_Actual_Buggy_A,%.6f%%%n", percentage(transitions.preventableActualBuggy, resultsA.actualBuggy));
        }
    }

    private static void writeDatasetEvaluation(PrintWriter writer, String datasetName, DatasetEvaluation result) {

        writer.printf(Locale.US, "%s,%d,%d,%d,%d,%d,%d,%d%n", datasetName, result.instances, result.actualBuggy, result.predictedBuggy, result.truePositives, result.falsePositives, result.falseNegatives, result.trueNegatives);
    }

    private static String escapeCsv(String value) {

        if (value == null) {
            return "";
        }

        String escaped = value.replace("\"", "\"\"");

        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n") || escaped.contains("\r")) {

            return "\"" + escaped + "\"";
        }

        return escaped;
    }

    /*
     * ==========================================================
     * DATA CLASSES
     * ==========================================================
     */

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

    private static class InstanceIdentifier {

        private final String projectName;
        private final String className;
        private final int releaseId;
        private final double originalSmells;

        private InstanceIdentifier(String projectName, String className, int releaseId, double originalSmells) {

            this.projectName = projectName;
            this.className = className;
            this.releaseId = releaseId;
            this.originalSmells = originalSmells;
        }
    }

    private static class DatasetEvaluation {

        private final long instances;
        private final long actualBuggy;
        private final long predictedBuggy;
        private final long truePositives;
        private final long falsePositives;
        private final long falseNegatives;
        private final long trueNegatives;

        private DatasetEvaluation(long instances, long actualBuggy, long predictedBuggy, long truePositives, long falsePositives, long falseNegatives, long trueNegatives) {

            this.instances = instances;
            this.actualBuggy = actualBuggy;
            this.predictedBuggy = predictedBuggy;
            this.truePositives = truePositives;
            this.falsePositives = falsePositives;
            this.falseNegatives = falseNegatives;
            this.trueNegatives = trueNegatives;
        }
    }

    private static class TransitionResults {

        private final long yesToNo;
        private final long noToYes;
        private final long yesToYes;
        private final long noToNo;

        private final long netPredictedReduction;
        private final long preventableActualBuggy;
        private final long actualBuggyInBPlus;
        private final long predictedBuggyInBPlus;

        private final double preventableOutOfActualBPlus;
        private final double preventableOutOfPredictedBPlus;

        private TransitionResults(long yesToNo, long noToYes, long yesToYes, long noToNo, long netPredictedReduction, long preventableActualBuggy, long actualBuggyInBPlus, long predictedBuggyInBPlus, double preventableOutOfActualBPlus, double preventableOutOfPredictedBPlus) {

            this.yesToNo = yesToNo;
            this.noToYes = noToYes;
            this.yesToYes = yesToYes;
            this.noToNo = noToNo;
            this.netPredictedReduction = netPredictedReduction;
            this.preventableActualBuggy = preventableActualBuggy;
            this.actualBuggyInBPlus = actualBuggyInBPlus;
            this.predictedBuggyInBPlus = predictedBuggyInBPlus;
            this.preventableOutOfActualBPlus = preventableOutOfActualBPlus;
            this.preventableOutOfPredictedBPlus = preventableOutOfPredictedBPlus;
        }
    }
}