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

package org.apache.ignite.ml.math.stat;

import java.util.Collections;
import java.util.List;
import java.util.stream.DoubleStream;
import org.apache.ignite.internal.util.typedef.internal.A;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.math.primitives.vector.VectorUtils;

public abstract class DistributionMixture<C extends Distribution> implements Distribution {
    private final double EPS = 1e-5;

    private final Vector componentProbs;
    private final List<C> distributions;
    private final int dimension;

    public DistributionMixture(Vector componentProbs, List<C> distributions) {
        A.ensure(DoubleStream.of(componentProbs.asArray()).allMatch(v -> v > 0), "All distribution components should be greater than zero");
        A.ensure(Math.abs(componentProbs.sum() - 1.) < EPS, "Components distribution should be nomalized");

        A.ensure(!distributions.isEmpty(), "Distribution mixture should have at least one component");

        final int dimension = distributions.get(0).dimension();
        A.ensure(dimension > 0, "Dimension should be greater than zero");
        A.ensure(distributions.stream().allMatch(d -> d.dimension() == dimension), "All distributions should have same dimension");

        this.distributions = distributions;
        this.componentProbs = componentProbs;
        this.dimension = dimension;
    }

    @Override public double prob(Vector x) {
        return likelihood(x).sum();
    }

    public Vector likelihood(Vector x) {
        return VectorUtils.of(distributions.stream().mapToDouble(f -> f.prob(x)).toArray())
            .times(componentProbs);
    }

    public int countOfComponents() {
        return componentProbs.size();
    }

    public Vector componentsProbs() {
        return componentProbs.copy();
    }

    public List<C> distributions() {
        return Collections.unmodifiableList(distributions);
    }

    @Override public int dimension() {
        return dimension;
    }
}
