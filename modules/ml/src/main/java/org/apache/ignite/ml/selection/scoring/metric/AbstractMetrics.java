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

package org.apache.ignite.ml.selection.scoring.metric;

import org.apache.ignite.ml.selection.scoring.LabelPair;

import java.util.Iterator;
import java.util.function.Function;

/**
 * Abstract metrics calculator.
 * It could be used in two ways: to caculate all regression metrics or custom regression metric.
 */
public abstract class AbstractMetrics<M extends MetricValues> implements Metric<Double> {
    /** The main metric to get individual score. */
    protected Function<M, Double> metric;

    /**
     * Calculates metrics values.
     *
     * @param iter Iterator that supplies pairs of truth values and predicated.
     * @return Scores for all regression metrics.
     */
    public abstract M scoreAll(Iterator<LabelPair<Double>> iter);

    /**
     *
     */
    public AbstractMetrics withMetric(Function<M, Double> metric) {
        if (metric != null)
            this.metric = metric;
        return this;
    }

    /** {@inheritDoc} */
    @Override public double score(Iterator<LabelPair<Double>> iter) {
        return metric.apply(scoreAll(iter));
    }
}
