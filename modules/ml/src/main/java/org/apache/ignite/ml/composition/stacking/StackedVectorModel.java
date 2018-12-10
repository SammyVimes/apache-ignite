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

package org.apache.ignite.ml.composition.stacking;

import org.apache.ignite.ml.Model;
import org.apache.ignite.ml.math.functions.IgniteBinaryOperator;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.math.primitives.vector.VectorUtils;
import org.apache.ignite.ml.trainers.DatasetTrainer;

public class StackedVectorModel<O, AM extends Model<Vector, O>, L> extends SimpleStackedModelTrainer<Vector, O, AM, L> {
    /**
     * Construct instance of this class with given aggregator trainer and aggregator input merger.
     *
     * @param aggregatorTrainer Aggregator trainer.
     * @param aggregatorInputMerger Function used to merge submodels outputs into one.
     */
    public StackedVectorModel(DatasetTrainer<AM, L> aggregatorTrainer,
        IgniteBinaryOperator<Vector> aggregatorInputMerger) {
        super(aggregatorTrainer, aggregatorInputMerger);
    }

    /**
     * Construct instance of this class with given aggregator trainer.
     *
     * @param aggregatorTrainer Aggregator trainer.
     */
    public StackedVectorModel(DatasetTrainer<AM, L> aggregatorTrainer) {
        super(aggregatorTrainer, VectorUtils::concat);
    }

    /**
     * Construct instance of this class.
     */
    public StackedVectorModel() {
        this(null);
    }
}
