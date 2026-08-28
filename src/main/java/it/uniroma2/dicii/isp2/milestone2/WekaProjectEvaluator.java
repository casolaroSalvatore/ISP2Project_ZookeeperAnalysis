package it.uniroma2.dicii.isp2.milestone2;

import weka.attributeSelection.CfsSubsetEval;
import weka.attributeSelection.GreedyStepwise;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.classifiers.bayes.NaiveBayes;
import weka.classifiers.lazy.IBk;
import weka.classifiers.trees.RandomForest;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.converters.CSVLoader;
import weka.filters.Filter;
import weka.filters.supervised.instance.SMOTE;
import weka.filters.unsupervised.attribute.Normalize;
import weka.filters.unsupervised.attribute.Remove;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class WekaProjectEvaluator {

    private static final String FULL_DATASET = "C:\\Users\\casol\\Desktop\\ISPW2\\ISP2Project_ZookeeperAnalysis\\" + "Milestone1_DatasetCreation\\ZOOKEEPER_Final_Dataset.csv";

    private static final String BEFORE_DISCARD_DIR = "C:\\Users\\casol\\Desktop\\ISPW2\\ISP2Project_ZookeeperAnalysis\\" + "Milestone2_ClassifierAccuracy\\BeforeDiscard";

    private static final String AFTER_DISCARD_DIR = "C:\\Users\\casol\\Desktop\\ISPW2\\ISP2Project_ZookeeperAnalysis\\" + "Milestone2_ClassifierAccuracy\\AfterDiscard";

    private static final String RESULTS_10FOLD_BEFORE = BEFORE_DISCARD_DIR + "\\Results_10x10Fold_Averages.csv";

    private static final String RESULTS_WF_BEFORE = BEFORE_DISCARD_DIR + "\\Results_WalkForward_Averages.csv";

    private static final String RESULTS_10FOLD_AFTER = AFTER_DISCARD_DIR + "\\Results_10x10Fold_Averages.csv";

    private static final String RESULTS_WF_AFTER = AFTER_DISCARD_DIR + "\\Results_WalkForward_Averages.csv";

    private static final String DETAILS_10FOLD_BEFORE = BEFORE_DISCARD_DIR + "\\Results_10x10Fold_PerRelease.csv";

    private static final String DETAILS_WF_BEFORE = BEFORE_DISCARD_DIR + "\\Results_WalkForward_PerRelease.csv";

    private static final String DETAILS_10FOLD_AFTER = AFTER_DISCARD_DIR + "\\Results_10x10Fold_PerRelease.csv";

    private static final String DETAILS_WF_AFTER = AFTER_DISCARD_DIR + "\\Results_WalkForward_PerRelease.csv";

    private static final String ERRORS_FILE = AFTER_DISCARD_DIR + "\\Execution_Errors.csv";

    private static final String PROJECT_COLUMN = "Project_Name";
    private static final String CLASS_COLUMN = "Class_Name";
    private static final String RELEASE_COLUMN = "Release_ID";
    private static final String CHURN_COLUMN = "Churn_Release";
    private static final String LOC_COLUMN = "Size_LOC";
    private static final String SMELL_COLUMN = "NSmells";
    private static final String POSITIVE_CLASS = "YES";

    private static final int REPETITIONS = 10;
    private static final int FOLDS = 10;

    private static final boolean[] FS_OPTIONS = {false, true};
    private static final boolean[] BALANCING_OPTIONS = {false, true};

    private static PrintWriter errorWriter;

    public static void main(String[] args) throws Exception {

        Locale.setDefault(Locale.US);

        createOutputDirectories();

        try (PrintWriter errors = new PrintWriter(new FileWriter(ERRORS_FILE))) {
            errorWriter = errors;
            errorWriter.println("Protocol,Release,Classifier,FS,Balancing,Seed,Fold,Error");

            Instances allData = loadDataset(FULL_DATASET);
            validateDataset(allData);

            Classifier[] classifiers = {new RandomForest(), new NaiveBayes(), new IBk()};

            String[] classifierNames = {"RandomForest", "NaiveBayes", "IBk"};

            System.out.println("==============================================");
            System.out.println("STARTING WEKA PROJECT EVALUATION");
            System.out.println("==============================================");

            /*
             * BeforeDiscard:
             * tutte le release vengono considerate.
             * Le metriche non definite rimangono NaN e non vengono
             * trasformate artificialmente in zero.
             */
            run10Times10Fold(allData, classifiers, classifierNames, false, RESULTS_10FOLD_BEFORE, DETAILS_10FOLD_BEFORE);

            runWalkForward(allData, classifiers, classifierNames, false, RESULTS_WF_BEFORE, DETAILS_WF_BEFORE);

            /*
             * AfterDiscard:
             * vengono escluse automaticamente le valutazioni in cui
             * il dataset sottoposto a test non contiene entrambe le classi.
             */
            run10Times10Fold(allData, classifiers, classifierNames, true, RESULTS_10FOLD_AFTER, DETAILS_10FOLD_AFTER);

            runWalkForward(allData, classifiers, classifierNames, true, RESULTS_WF_AFTER, DETAILS_WF_AFTER);

            System.out.println("==============================================");
            System.out.println("PROCESSING COMPLETED");
            System.out.println("==============================================");
            System.out.println("Generated files:");
            System.out.println(" - " + RESULTS_10FOLD_BEFORE);
            System.out.println(" - " + RESULTS_WF_BEFORE);
            System.out.println(" - " + RESULTS_10FOLD_AFTER);
            System.out.println(" - " + RESULTS_WF_AFTER);
            System.out.println(" - " + DETAILS_10FOLD_BEFORE);
            System.out.println(" - " + DETAILS_WF_BEFORE);
            System.out.println(" - " + DETAILS_10FOLD_AFTER);
            System.out.println(" - " + DETAILS_WF_AFTER);
            System.out.println(" - " + ERRORS_FILE);
        }
    }

    private static void createOutputDirectories() {
        new File(BEFORE_DISCARD_DIR).mkdirs();
        new File(AFTER_DISCARD_DIR).mkdirs();
    }

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
                throw new IllegalArgumentException("No attributes were loaded from the dataset.");
            }

            data.setClassIndex(data.numAttributes() - 1);
            return data;
        }
    }

    private static void validateDataset(Instances data) {

        String[] requiredAttributes = {PROJECT_COLUMN, CLASS_COLUMN, RELEASE_COLUMN, CHURN_COLUMN, LOC_COLUMN, SMELL_COLUMN};

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
     * 10-TIMES 10-FOLD CROSS VALIDATION, PER RELEASE
     * ==========================================================
     */

    private static void run10Times10Fold(Instances allData, Classifier[] classifiers, String[] classifierNames, boolean discardSingleClassReleases, String averagesOutput, String detailsOutput) throws Exception {

        String mode = discardSingleClassReleases ? "AfterDiscard" : "BeforeDiscard";

        System.out.println();
        System.out.println("Executing 10-times 10-fold cross-validation: " + mode);

        List<Integer> releases = getReleaseIds(allData);

        try (PrintWriter averageWriter = new PrintWriter(new FileWriter(averagesOutput)); PrintWriter detailWriter = new PrintWriter(new FileWriter(detailsOutput))) {

            averageWriter.println("Dataset,Protocol,Classifier,FS,Balancing," + "Precision,Recall,AUC,Kappa,NPofB20," + "Valid_Releases");

            detailWriter.println("Dataset,Release_ID,Classifier,FS,Balancing," + "Precision,Recall,AUC,Kappa,NPofB20," + "Valid_Repetitions");

            int totalConfigurations = classifiers.length * FS_OPTIONS.length * BALANCING_OPTIONS.length;

            int currentConfiguration = 0;

            for (int modelIndex = 0; modelIndex < classifiers.length; modelIndex++) {

                for (boolean useFeatureSelection : FS_OPTIONS) {
                    for (boolean useBalancing : BALANCING_OPTIONS) {

                        currentConfiguration++;

                        String fsValue = useFeatureSelection ? "Yes" : "No";

                        String balancingValue = useBalancing ? "Yes" : "No";

                        System.out.printf(Locale.US, "[10x10 %s] [%d/%d] %s | FS=%s | BAL=%s%n", mode, currentConfiguration, totalConfigurations, classifierNames[modelIndex], fsValue, balancingValue);

                        MetricAccumulator globalMetrics = new MetricAccumulator();

                        int validReleases = 0;

                        for (int releaseId : releases) {

                            Instances releaseData = subsetByRelease(allData, releaseId);

                            if (releaseData.numInstances() < FOLDS) {
                                logError("10x10-" + mode, releaseId, classifierNames[modelIndex], fsValue, balancingValue, -1, -1, "Less than " + FOLDS + " instances");
                                continue;
                            }

                            if (discardSingleClassReleases && !containsBothClasses(releaseData)) {

                                logError("10x10-" + mode, releaseId, classifierNames[modelIndex], fsValue, balancingValue, -1, -1, "Release discarded because " + "it does not contain both classes");
                                continue;
                            }

                            MetricAccumulator releaseMetrics = evaluateRepeatedCrossValidation(releaseData, classifiers[modelIndex], classifierNames[modelIndex], useFeatureSelection, useBalancing, releaseId, mode);

                            if (releaseMetrics.getValidRuns() == 0) {
                                continue;
                            }

                            EvaluationResult releaseResult = releaseMetrics.toResult();

                            detailWriter.printf(Locale.US, "ZooKeeper,%d,%s,%s,%s,%s,%s,%s,%s,%s,%d%n", releaseId, classifierNames[modelIndex], fsValue, balancingValue, formatMetric(releaseResult.precision), formatMetric(releaseResult.recall), formatMetric(releaseResult.auc), formatMetric(releaseResult.kappa), formatMetric(releaseResult.npofb20), releaseMetrics.getValidRuns());

                            globalMetrics.addResult(releaseResult);
                            validReleases++;
                        }

                        EvaluationResult globalResult = globalMetrics.toResult();

                        averageWriter.printf(Locale.US, "ZooKeeper,10x10Fold,%s,%s,%s," + "%s,%s,%s,%s,%s,%d%n", classifierNames[modelIndex], fsValue, balancingValue, formatMetric(globalResult.precision), formatMetric(globalResult.recall), formatMetric(globalResult.auc), formatMetric(globalResult.kappa), formatMetric(globalResult.npofb20), validReleases);

                        averageWriter.flush();
                        detailWriter.flush();
                    }
                }
            }
        }
    }

    private static MetricAccumulator evaluateRepeatedCrossValidation(Instances originalReleaseData, Classifier baseClassifier, String classifierName, boolean useFeatureSelection, boolean useBalancing, int releaseId, String mode) {

        MetricAccumulator repetitionMetrics = new MetricAccumulator();

        for (int seed = 1; seed <= REPETITIONS; seed++) {

            try {
                Instances randomized = new Instances(originalReleaseData);

                randomized.randomize(new Random(seed));

                if (randomized.classAttribute().isNominal()) {
                    randomized.stratify(FOLDS);
                }

                Evaluation evaluation = new Evaluation(randomized);
                List<PredictionRecord> outOfFoldPredictions = new ArrayList<>();

                int successfulFolds = 0;

                for (int fold = 0; fold < FOLDS; fold++) {

                    Instances train = randomized.trainCV(FOLDS, fold, new Random(seed));

                    Instances test = randomized.testCV(FOLDS, fold);

                    if (train.numInstances() == 0 || test.numInstances() == 0) {
                        continue;
                    }

                    PreparedData prepared = prepareData(train, test, useFeatureSelection, useBalancing, seed);

                    int yesIndex = getYesIndex(prepared.train);

                    Classifier classifier = AbstractClassifier.makeCopy(baseClassifier);

                    classifier.buildClassifier(prepared.train);
                    evaluation.evaluateModel(classifier, prepared.test);

                    appendPredictionRecords(classifier, prepared.test, prepared.testLocValues, yesIndex, outOfFoldPredictions);

                    successfulFolds++;
                }

                if (successfulFolds != FOLDS) {
                    logError("10x10-" + mode, releaseId, classifierName, useFeatureSelection ? "Yes" : "No", useBalancing ? "Yes" : "No", seed, -1, "Only " + successfulFolds + " out of " + FOLDS + " folds completed");

                    continue;
                }

                int yesIndex = getYesIndex(randomized);

                EvaluationResult result = new EvaluationResult(evaluation.precision(yesIndex), evaluation.recall(yesIndex), evaluation.areaUnderROC(yesIndex), evaluation.kappa(), calculateNPofB20(outOfFoldPredictions));

                repetitionMetrics.addResult(result);

            } catch (Exception exception) {
                logError("10x10-" + mode, releaseId, classifierName, useFeatureSelection ? "Yes" : "No", useBalancing ? "Yes" : "No", seed, -1, exception.getClass().getSimpleName() + ": " + exception.getMessage());
            }
        }

        return repetitionMetrics;
    }

    /*
     * ==========================================================
     * WALK-FORWARD VALIDATION
     * ==========================================================
     */

    private static void runWalkForward(Instances allData, Classifier[] classifiers, String[] classifierNames, boolean discardSingleClassTestSets, String averagesOutput, String detailsOutput) throws Exception {

        String mode = discardSingleClassTestSets ? "AfterDiscard" : "BeforeDiscard";

        System.out.println();
        System.out.println("Executing Walk-Forward validation: " + mode);

        List<Integer> releases = getReleaseIds(allData);

        try (PrintWriter averageWriter = new PrintWriter(new FileWriter(averagesOutput)); PrintWriter detailWriter = new PrintWriter(new FileWriter(detailsOutput))) {

            averageWriter.println("Dataset,Protocol,Classifier,FS,Balancing," + "Precision,Recall,AUC,Kappa,NPofB20,Valid_Runs");

            detailWriter.println("Dataset,Train_Through_Release,Test_Release," + "Classifier,FS,Balancing,Precision,Recall," + "AUC,Kappa,NPofB20,Train_Instances,Test_Instances");

            int totalConfigurations = classifiers.length * FS_OPTIONS.length * BALANCING_OPTIONS.length;

            int currentConfiguration = 0;

            for (int modelIndex = 0; modelIndex < classifiers.length; modelIndex++) {

                for (boolean useFeatureSelection : FS_OPTIONS) {
                    for (boolean useBalancing : BALANCING_OPTIONS) {

                        currentConfiguration++;

                        String fsValue = useFeatureSelection ? "Yes" : "No";

                        String balancingValue = useBalancing ? "Yes" : "No";

                        System.out.printf(Locale.US, "[WF %s] [%d/%d] %s | FS=%s | BAL=%s%n", mode, currentConfiguration, totalConfigurations, classifierNames[modelIndex], fsValue, balancingValue);

                        MetricAccumulator globalMetrics = new MetricAccumulator();

                        int validRuns = 0;

                        for (int releasePosition = 0; releasePosition < releases.size() - 1; releasePosition++) {

                            int trainThroughRelease = releases.get(releasePosition);

                            int testRelease = releases.get(releasePosition + 1);

                            Instances train = subsetUpToRelease(allData, trainThroughRelease);

                            Instances test = subsetByReleaseAndPositiveChurn(allData, testRelease);

                            if (train.numInstances() == 0 || test.numInstances() == 0) {

                                logError("WalkForward-" + mode, testRelease, classifierNames[modelIndex], fsValue, balancingValue, -1, -1, "Empty training or testing set");

                                continue;
                            }

                            if (discardSingleClassTestSets && !containsBothClasses(test)) {

                                logError("WalkForward-" + mode, testRelease, classifierNames[modelIndex], fsValue, balancingValue, -1, -1, "Test release discarded because " + "it does not contain both classes");

                                continue;
                            }

                            try {
                                PreparedData prepared = prepareData(train, test, useFeatureSelection, useBalancing, testRelease);

                                int yesIndex = getYesIndex(prepared.train);

                                Classifier classifier = AbstractClassifier.makeCopy(classifiers[modelIndex]);

                                classifier.buildClassifier(prepared.train);

                                Evaluation evaluation = new Evaluation(prepared.train);

                                evaluation.evaluateModel(classifier, prepared.test);

                                List<PredictionRecord> predictions = new ArrayList<>();

                                appendPredictionRecords(classifier, prepared.test, prepared.testLocValues, yesIndex, predictions);

                                EvaluationResult result = new EvaluationResult(evaluation.precision(yesIndex), evaluation.recall(yesIndex), evaluation.areaUnderROC(yesIndex), evaluation.kappa(), calculateNPofB20(predictions));

                                detailWriter.printf(Locale.US, "ZooKeeper,%d,%d,%s,%s,%s," + "%s,%s,%s,%s,%s,%d,%d%n", trainThroughRelease, testRelease, classifierNames[modelIndex], fsValue, balancingValue, formatMetric(result.precision), formatMetric(result.recall), formatMetric(result.auc), formatMetric(result.kappa), formatMetric(result.npofb20), prepared.train.numInstances(), prepared.test.numInstances());

                                globalMetrics.addResult(result);
                                validRuns++;

                            } catch (Exception exception) {
                                logError("WalkForward-" + mode, testRelease, classifierNames[modelIndex], fsValue, balancingValue, -1, -1, exception.getClass().getSimpleName() + ": " + exception.getMessage());
                            }
                        }

                        EvaluationResult globalResult = globalMetrics.toResult();

                        averageWriter.printf(Locale.US, "ZooKeeper,WalkForward,%s,%s,%s," + "%s,%s,%s,%s,%s,%d%n", classifierNames[modelIndex], fsValue, balancingValue, formatMetric(globalResult.precision), formatMetric(globalResult.recall), formatMetric(globalResult.auc), formatMetric(globalResult.kappa), formatMetric(globalResult.npofb20), validRuns);

                        averageWriter.flush();
                        detailWriter.flush();
                    }
                }
            }
        }
    }

    /*
     * ==========================================================
     * PRE-PROCESSING
     * ==========================================================
     */

    private static PreparedData prepareData(Instances originalTrain, Instances originalTest, boolean useFeatureSelection, boolean useBalancing, int randomSeed) throws Exception {

        double[] testLocValues = extractAttributeValues(originalTest, LOC_COLUMN);

        Instances train = new Instances(originalTrain);
        Instances test = new Instances(originalTest);

        train.setClassIndex(train.numAttributes() - 1);
        test.setClassIndex(test.numAttributes() - 1);

        /*
         * The identifiers are always removed, independently
         * of Feature Selection.
         */
        int[] identifierIndices = findAttributeIndices(train, PROJECT_COLUMN, CLASS_COLUMN, RELEASE_COLUMN);

        DatasetPair withoutIdentifiers = applyRemove(train, test, identifierIndices, false);

        train = withoutIdentifiers.train;
        test = withoutIdentifiers.test;

        /*
         * Feature Selection is learned exclusively on the training set.
         */
        if (useFeatureSelection) {
            DatasetPair selectedData = applyFeatureSelection(train, test, SMELL_COLUMN);

            train = selectedData.train;
            test = selectedData.test;
        }

        /*
         * Normalize is learned on the training set and then applied
         * unchanged to the testing set.
         */
        DatasetPair normalizedData = applyNormalize(train, test);
        train = normalizedData.train;
        test = normalizedData.test;

        /*
         * SMOTE is applied exclusively to the training set.
         */
        if (useBalancing) {
            train = applySmote(train, randomSeed);
        }

        train.setClassIndex(train.numAttributes() - 1);
        test.setClassIndex(test.numAttributes() - 1);

        if (!train.equalHeaders(test)) {
            throw new IllegalStateException("Training and testing sets have incompatible headers: " + train.equalHeadersMsg(test));
        }

        return new PreparedData(train, test, testLocValues);
    }

    private static DatasetPair applyRemove(Instances train, Instances test, int[] indices, boolean invertSelection) throws Exception {

        Remove remove = new Remove();
        remove.setAttributeIndicesArray(indices);
        remove.setInvertSelection(invertSelection);
        remove.setInputFormat(train);

        Instances filteredTrain = Filter.useFilter(train, remove);
        Instances filteredTest = Filter.useFilter(test, remove);

        filteredTrain.setClassIndex(filteredTrain.numAttributes() - 1);
        filteredTest.setClassIndex(filteredTest.numAttributes() - 1);

        return new DatasetPair(filteredTrain, filteredTest);
    }

    private static DatasetPair applyFeatureSelection(Instances train, Instances test, String mandatoryAttribute) throws Exception {

        weka.attributeSelection.AttributeSelection selector = new weka.attributeSelection.AttributeSelection();

        selector.setEvaluator(new CfsSubsetEval());

        GreedyStepwise search = new GreedyStepwise();
        search.setSearchBackwards(false);
        selector.setSearch(search);

        selector.SelectAttributes(train);

        int[] selectedIndices = selector.selectedAttributes();

        List<Integer> indicesToKeep = new ArrayList<>();

        for (int index : selectedIndices) {
            if (!indicesToKeep.contains(index)) {
                indicesToKeep.add(index);
            }
        }

        if (train.attribute(mandatoryAttribute) != null) {
            int mandatoryIndex = train.attribute(mandatoryAttribute).index();

            if (!indicesToKeep.contains(mandatoryIndex)) {
                indicesToKeep.add(mandatoryIndex);
            }
        }

        if (!indicesToKeep.contains(train.classIndex())) {
            indicesToKeep.add(train.classIndex());
        }

        int[] indices = indicesToKeep.stream().mapToInt(Integer::intValue).sorted().toArray();

        return applyRemove(train, test, indices, true);
    }

    private static DatasetPair applyNormalize(Instances train, Instances test) throws Exception {

        Normalize normalize = new Normalize();
        normalize.setIgnoreClass(true);
        normalize.setInputFormat(train);

        Instances normalizedTrain = Filter.useFilter(train, normalize);

        Instances normalizedTest = Filter.useFilter(test, normalize);

        normalizedTrain.setClassIndex(normalizedTrain.numAttributes() - 1);

        normalizedTest.setClassIndex(normalizedTest.numAttributes() - 1);

        return new DatasetPair(normalizedTrain, normalizedTest);
    }

    private static Instances applySmote(Instances train, int randomSeed) throws Exception {

        int minorityCount = getMinorityClassCount(train);

        if (minorityCount < 2) {
            throw new IllegalStateException("SMOTE cannot be applied because the minority " + "class contains fewer than 2 instances.");
        }

        int nearestNeighbours = Math.min(5, minorityCount - 1);

        SMOTE smote = new SMOTE();
        smote.setRandomSeed(randomSeed);
        smote.setNearestNeighbors(nearestNeighbours);
        smote.setInputFormat(train);

        Instances balancedTrain = Filter.useFilter(train, smote);

        balancedTrain.setClassIndex(balancedTrain.numAttributes() - 1);

        return balancedTrain;
    }

    /*
     * ==========================================================
     * NPofB20
     * ==========================================================
     */

    private static void appendPredictionRecords(Classifier classifier, Instances transformedTest, double[] originalLocValues, int yesIndex, List<PredictionRecord> records) throws Exception {

        if (transformedTest.numInstances() != originalLocValues.length) {

            throw new IllegalArgumentException("Mismatch between test instances and LOC values.");
        }

        for (int i = 0; i < transformedTest.numInstances(); i++) {

            Instance instance = transformedTest.instance(i);

            double[] distribution = classifier.distributionForInstance(instance);

            double probabilityYes = distribution[yesIndex];

            boolean actuallyBuggy = (int) instance.classValue() == yesIndex;

            records.add(new PredictionRecord(probabilityYes, originalLocValues[i], actuallyBuggy));
        }
    }

    private static double calculateNPofB20(List<PredictionRecord> records) {

        if (records.isEmpty()) {
            return Double.NaN;
        }

        double totalLoc = 0.0;
        int totalBuggy = 0;

        for (PredictionRecord record : records) {
            totalLoc += Math.max(0.0, record.loc);

            if (record.actuallyBuggy) {
                totalBuggy++;
            }
        }

        if (totalBuggy == 0 || totalLoc <= 0.0) {
            return Double.NaN;
        }

        records.sort((first, second) -> Double.compare(second.probabilityYes, first.probabilityYes));

        double locBudget = totalLoc * 0.20;
        double inspectedLoc = 0.0;
        int bugsFound = 0;

        for (PredictionRecord record : records) {

            if (inspectedLoc >= locBudget) {
                break;
            }

            inspectedLoc += Math.max(0.0, record.loc);

            if (record.actuallyBuggy) {
                bugsFound++;
            }
        }

        return (double) bugsFound / totalBuggy;
    }

    /*
     * ==========================================================
     * DATASET SUBSETS
     * ==========================================================
     */

    private static Instances subsetByRelease(Instances source, int releaseId) {

        int releaseIndex = source.attribute(RELEASE_COLUMN).index();

        Instances subset = new Instances(source, 0);

        for (Instance instance : source) {
            if ((int) instance.value(releaseIndex) == releaseId) {
                subset.add(instance);
            }
        }

        subset.setClassIndex(source.classIndex());
        return subset;
    }

    private static Instances subsetUpToRelease(Instances source, int maximumRelease) {

        int releaseIndex = source.attribute(RELEASE_COLUMN).index();

        Instances subset = new Instances(source, 0);

        for (Instance instance : source) {
            if ((int) instance.value(releaseIndex) <= maximumRelease) {

                subset.add(instance);
            }
        }

        subset.setClassIndex(source.classIndex());
        return subset;
    }

    private static Instances subsetByReleaseAndPositiveChurn(Instances source, int releaseId) {

        int releaseIndex = source.attribute(RELEASE_COLUMN).index();

        int churnIndex = source.attribute(CHURN_COLUMN).index();

        Instances subset = new Instances(source, 0);

        for (Instance instance : source) {
            boolean expectedRelease = (int) instance.value(releaseIndex) == releaseId;

            boolean positiveChurn = instance.value(churnIndex) > 0.0;

            if (expectedRelease && positiveChurn) {
                subset.add(instance);
            }
        }

        subset.setClassIndex(source.classIndex());
        return subset;
    }

    private static List<Integer> getReleaseIds(Instances data) {

        int releaseIndex = data.attribute(RELEASE_COLUMN).index();

        List<Integer> releases = new ArrayList<>();

        for (Instance instance : data) {
            int releaseId = (int) instance.value(releaseIndex);

            if (!releases.contains(releaseId)) {
                releases.add(releaseId);
            }
        }

        releases.sort(Integer::compareTo);
        return releases;
    }

    /*
     * ==========================================================
     * VALIDATION HELPERS
     * ==========================================================
     */

    private static boolean containsBothClasses(Instances data) {

        if (!data.classAttribute().isNominal() || data.classAttribute().numValues() < 2) {
            return false;
        }

        boolean[] observed = new boolean[data.classAttribute().numValues()];

        for (Instance instance : data) {
            if (!instance.classIsMissing()) {
                observed[(int) instance.classValue()] = true;
            }
        }

        int observedClasses = 0;

        for (boolean value : observed) {
            if (value) {
                observedClasses++;
            }
        }

        return observedClasses >= 2;
    }

    private static int getYesIndex(Instances data) {

        int index = data.classAttribute().indexOfValue(POSITIVE_CLASS);

        if (index < 0) {
            throw new IllegalArgumentException("Positive class '" + POSITIVE_CLASS + "' not found.");
        }

        return index;
    }

    private static int getMinorityClassCount(Instances data) {

        int[] counts = new int[data.classAttribute().numValues()];

        for (Instance instance : data) {
            if (!instance.classIsMissing()) {
                counts[(int) instance.classValue()]++;
            }
        }

        return Arrays.stream(counts).filter(value -> value > 0).min().orElse(0);
    }

    private static int[] findAttributeIndices(Instances data, String... names) {

        int[] indices = new int[names.length];

        for (int i = 0; i < names.length; i++) {
            if (data.attribute(names[i]) == null) {
                throw new IllegalArgumentException("Attribute not found: " + names[i]);
            }

            indices[i] = data.attribute(names[i]).index();
        }

        return indices;
    }

    private static double[] extractAttributeValues(Instances data, String attributeName) {

        if (data.attribute(attributeName) == null) {
            throw new IllegalArgumentException("Attribute not found: " + attributeName);
        }

        int attributeIndex = data.attribute(attributeName).index();

        double[] values = new double[data.numInstances()];

        for (int i = 0; i < data.numInstances(); i++) {
            values[i] = data.instance(i).value(attributeIndex);
        }

        return values;
    }

    private static String formatMetric(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "NaN";
        }

        return String.format(Locale.US, "%.4f", value);
    }

    private static void logError(String protocol, int release, String classifier, String featureSelection, String balancing, int seed, int fold, String message) {

        String sanitizedMessage = message == null ? "Unknown error" : message.replace(",", ";").replace("\n", " ").replace("\r", " ");

        errorWriter.printf(Locale.US, "%s,%d,%s,%s,%s,%d,%d,%s%n", protocol, release, classifier, featureSelection, balancing, seed, fold, sanitizedMessage);

        errorWriter.flush();

        System.err.printf("ERROR [%s] release=%d classifier=%s " + "FS=%s balancing=%s seed=%d fold=%d: %s%n", protocol, release, classifier, featureSelection, balancing, seed, fold, sanitizedMessage);
    }

    /*
     * ==========================================================
     * DATA CLASSES
     * ==========================================================
     */

    private static class DatasetPair {
        private final Instances train;
        private final Instances test;

        private DatasetPair(Instances train, Instances test) {

            this.train = train;
            this.test = test;
        }
    }

    private static class PreparedData {
        private final Instances train;
        private final Instances test;
        private final double[] testLocValues;

        private PreparedData(Instances train, Instances test, double[] testLocValues) {

            this.train = train;
            this.test = test;
            this.testLocValues = testLocValues;
        }
    }

    private static class PredictionRecord {
        private final double probabilityYes;
        private final double loc;
        private final boolean actuallyBuggy;

        private PredictionRecord(double probabilityYes, double loc, boolean actuallyBuggy) {

            this.probabilityYes = probabilityYes;
            this.loc = loc;
            this.actuallyBuggy = actuallyBuggy;
        }
    }

    private static class EvaluationResult {
        private final double precision;
        private final double recall;
        private final double auc;
        private final double kappa;
        private final double npofb20;

        private EvaluationResult(double precision, double recall, double auc, double kappa, double npofb20) {

            this.precision = precision;
            this.recall = recall;
            this.auc = auc;
            this.kappa = kappa;
            this.npofb20 = npofb20;
        }
    }

    private static class MetricAccumulator {

        private double precisionSum;
        private double recallSum;
        private double aucSum;
        private double kappaSum;
        private double npofb20Sum;

        private int precisionCount;
        private int recallCount;
        private int aucCount;
        private int kappaCount;
        private int npofb20Count;

        private int validRuns;

        private void addResult(EvaluationResult result) {

            boolean atLeastOneValidMetric = false;

            if (isFinite(result.precision)) {
                precisionSum += result.precision;
                precisionCount++;
                atLeastOneValidMetric = true;
            }

            if (isFinite(result.recall)) {
                recallSum += result.recall;
                recallCount++;
                atLeastOneValidMetric = true;
            }

            if (isFinite(result.auc)) {
                aucSum += result.auc;
                aucCount++;
                atLeastOneValidMetric = true;
            }

            if (isFinite(result.kappa)) {
                kappaSum += result.kappa;
                kappaCount++;
                atLeastOneValidMetric = true;
            }

            if (isFinite(result.npofb20)) {
                npofb20Sum += result.npofb20;
                npofb20Count++;
                atLeastOneValidMetric = true;
            }

            if (atLeastOneValidMetric) {
                validRuns++;
            }
        }

        private EvaluationResult toResult() {
            return new EvaluationResult(average(precisionSum, precisionCount), average(recallSum, recallCount), average(aucSum, aucCount), average(kappaSum, kappaCount), average(npofb20Sum, npofb20Count));
        }

        private int getValidRuns() {
            return validRuns;
        }

        private static boolean isFinite(double value) {
            return !Double.isNaN(value) && !Double.isInfinite(value);
        }

        private static double average(double sum, int count) {

            return count == 0 ? Double.NaN : sum / count;
        }
    }
}