/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.examples.ml.svm.multiclass;

import java.io.FileNotFoundException;
import java.util.Arrays;
import javax.cache.Cache;
import org.apache.ignite.Ignite;
import org.apache.ignite.IgniteCache;
import org.apache.ignite.Ignition;
import org.apache.ignite.cache.query.QueryCursor;
import org.apache.ignite.cache.query.ScanQuery;
import org.apache.ignite.examples.ml.util.MLSandboxDatasets;
import org.apache.ignite.examples.ml.util.SandboxMLCache;
import org.apache.ignite.ml.math.functions.IgniteBiFunction;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.preprocessing.minmaxscaling.MinMaxScalerTrainer;
import org.apache.ignite.ml.svm.SVMLinearMultiClassClassificationModel;
import org.apache.ignite.ml.svm.SVMLinearMultiClassClassificationTrainer;

/**
 * Run SVM multi-class classification trainer ({@link SVMLinearMultiClassClassificationModel}) over distributed dataset
 * to build two models: one with minmaxscaling and one without minmaxscaling.
 * <p>
 * Code in this example launches Ignite grid and fills the cache with test data points (preprocessed
 * <a href="https://archive.ics.uci.edu/ml/datasets/Glass+Identification">Glass dataset</a>).</p>
 * <p>
 * After that it trains two SVM multi-class models based on the specified data - one model is with minmaxscaling
 * and one without minmaxscaling.</p>
 * <p>
 * Finally, this example loops over the test set of data points, applies the trained models to predict what cluster
 * does this point belong to, compares prediction to expected outcome (ground truth), and builds
 * <a href="https://en.wikipedia.org/wiki/Confusion_matrix">confusion matrix</a>.</p>
 * <p>
 * You can change the test data used in this example and re-run it to explore this algorithm further.</p>
 * NOTE: the smallest 3rd class could be classified via linear SVM here.
 */
public class SVMMultiClassClassificationExample {
    /** Run example. */
    public static void main(String[] args) throws FileNotFoundException {
        System.out.println();
        System.out.println(">>> SVM Multi-class classification model over cached dataset usage example started.");
        // Start ignite grid.
        try (Ignite ignite = Ignition.start("examples/config/example-ignite.xml")) {
            System.out.println(">>> Ignite grid started.");

            IgniteCache<Integer, Vector> dataCache = new SandboxMLCache(ignite)
                .fillCacheWith(MLSandboxDatasets.GLASS_IDENTIFICATION);

            SVMLinearMultiClassClassificationTrainer trainer = new SVMLinearMultiClassClassificationTrainer();

            SVMLinearMultiClassClassificationModel mdl = trainer.fit(
                ignite,
                dataCache,
                (k, v) -> v.copyOfRange(1, v.size()),
                (k, v) -> v.get(0)
            );

            System.out.println(">>> SVM Multi-class model");
            System.out.println(mdl.toString());

            MinMaxScalerTrainer<Integer, Vector> minMaxScalerTrainer = new MinMaxScalerTrainer<>();

            IgniteBiFunction<Integer, Vector, Vector> preprocessor = minMaxScalerTrainer.fit(
                ignite,
                dataCache,
                (k, v) -> v.copyOfRange(1, v.size())
            );

            SVMLinearMultiClassClassificationModel mdlWithScaling = trainer.fit(
                ignite,
                dataCache,
                preprocessor,
                (k, v) -> v.get(0)
            );

            System.out.println(">>> SVM Multi-class model with MinMaxScaling");
            System.out.println(mdlWithScaling.toString());

            System.out.println(">>> ----------------------------------------------------------------");
            System.out.println(">>> | Prediction\t| Prediction with MinMaxScaling\t| Ground Truth\t|");
            System.out.println(">>> ----------------------------------------------------------------");

            int amountOfErrors = 0;
            int amountOfErrorsWithMinMaxScaling = 0;
            int totalAmount = 0;

            // Build confusion matrix. See https://en.wikipedia.org/wiki/Confusion_matrix
            int[][] confusionMtx = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
            int[][] confusionMtxWithMinMaxScaling = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};

            try (QueryCursor<Cache.Entry<Integer, Vector>> observations = dataCache.query(new ScanQuery<>())) {
                for (Cache.Entry<Integer, Vector> observation : observations) {
                    Vector val = observation.getValue();
                    Vector inputs = val.copyOfRange(1, val.size());
                    double groundTruth = val.get(0);

                    double prediction = mdl.apply(inputs);
                    double predictionWithMinMaxScaling = mdlWithScaling.apply(inputs);

                    totalAmount++;

                    // Collect data for model
                    if(groundTruth != prediction)
                        amountOfErrors++;

                    int idx1 = (int)prediction == 1 ? 0 : ((int)prediction == 3 ? 1 : 2);
                    int idx2 = (int)groundTruth == 1 ? 0 : ((int)groundTruth == 3 ? 1 : 2);

                    confusionMtx[idx1][idx2]++;

                    // Collect data for model with minmaxscaling
                    if (groundTruth != predictionWithMinMaxScaling)
                        amountOfErrorsWithMinMaxScaling++;

                    idx1 = (int)predictionWithMinMaxScaling == 1 ? 0 : ((int)predictionWithMinMaxScaling == 3 ? 1 : 2);
                    idx2 = (int)groundTruth == 1 ? 0 : ((int)groundTruth == 3 ? 1 : 2);

                    confusionMtxWithMinMaxScaling[idx1][idx2]++;

                    System.out.printf(">>> | %.4f\t\t| %.4f\t\t\t\t\t\t| %.4f\t\t|\n", prediction, predictionWithMinMaxScaling, groundTruth);
                }
                System.out.println(">>> ----------------------------------------------------------------");
                System.out.println("\n>>> -----------------SVM model-------------");
                System.out.println("\n>>> Absolute amount of errors " + amountOfErrors);
                System.out.println("\n>>> Accuracy " + (1 - amountOfErrors / (double)totalAmount));
                System.out.println("\n>>> Confusion matrix is " + Arrays.deepToString(confusionMtx));

                System.out.println("\n>>> -----------------SVM model with MinMaxScaling-------------");
                System.out.println("\n>>> Absolute amount of errors " + amountOfErrorsWithMinMaxScaling);
                System.out.println("\n>>> Accuracy " + (1 - amountOfErrorsWithMinMaxScaling / (double)totalAmount));
                System.out.println("\n>>> Confusion matrix is " + Arrays.deepToString(confusionMtxWithMinMaxScaling));

                System.out.println(">>> Linear regression model over cache based dataset usage example completed.");
            }
        }
    }
}
