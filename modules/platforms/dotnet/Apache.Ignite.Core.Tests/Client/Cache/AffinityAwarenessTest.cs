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

namespace Apache.Ignite.Core.Tests.Client.Cache
{
    using System;
    using System.Collections.Generic;
    using System.Linq;
    using System.Net;
    using Apache.Ignite.Core.Client;
    using Apache.Ignite.Core.Client.Cache;
    using Apache.Ignite.Core.Log;
    using NUnit.Framework;

    /// <summary>
    /// Tests affinity awareness functionality.
    /// </summary>
    public class AffinityAwarenessTest : ClientTestBase
    {
        // TODO:
        // * Test disabled/enabled
        // * Test request routing (using local cache events)
        // * Test hash code for all primitives
        // * Test hash code for complex key
        // * Test hash code for complex key with AffinityKeyMapped
        // * Test topology update

        /** */
        private readonly List<ListLogger> _loggers = new List<ListLogger>();

        /** */
        private ICacheClient<int, int> _cache;

        /// <summary>
        /// Initializes a new instance of the <see cref="AffinityAwarenessTest"/> class.
        /// </summary>
        public AffinityAwarenessTest() : base(3)
        {
            // No-op.
        }

        /// <summary>
        /// Fixture set up.
        /// </summary>
        public override void FixtureSetUp()
        {
            base.FixtureSetUp();

            _cache = Client.CreateCache<int, int>("c");
            _cache.PutAll(Enumerable.Range(1, 100).ToDictionary(x => x, x => x));

            // Warm up client partition data.
            _cache.Get(1);
            _cache.Get(2);
        }

        public override void TestSetUp()
        {
            base.TestSetUp();

            foreach (var logger in _loggers)
            {
                logger.Clear();
            }
        }

        protected override IgniteConfiguration GetIgniteConfiguration()
        {
            var cfg = base.GetIgniteConfiguration();

            var logger = new ListLogger();
            cfg.Logger = logger;
            _loggers.Add(logger);

            return cfg;
        }

        protected override IgniteClientConfiguration GetClientConfiguration()
        {
            var cfg = base.GetClientConfiguration();

            cfg.EnableAffinityAwareness = true;
            cfg.Endpoints.Add(string.Format("{0}:{1}", IPAddress.Loopback, IgniteClientConfiguration.DefaultPort + 1));
            cfg.Endpoints.Add(string.Format("{0}:{1}", IPAddress.Loopback, IgniteClientConfiguration.DefaultPort + 2));

            return cfg;
        }

        private int GetClientRequestGridIndex()
        {
            for (var i = 0; i < _loggers.Count; i++)
            {
                var logger = _loggers[i];

                if (logger.Messages.Any(m => m.Contains("ClientCacheGetRequest")))
                {
                    return i;
                }
            }

            return -1;
        }

        [Test]
        [TestCase(1, 1)]
        [TestCase(2, 0)]
        [TestCase(3, 0)]
        [TestCase(4, 1)]
        [TestCase(5, 1)]
        [TestCase(6, 2)]
        public void CacheGet_PrimitiveKeyType_RequestIsRoutedToPrimaryNode(int key, int gridIdx)
        {
            var res = _cache.Get(key);

            Assert.AreEqual(key, res);
            Assert.AreEqual(gridIdx, GetClientRequestGridIndex());
        }

        [Test]
        [TestCase(1, 1)]
        [TestCase(2, 0)]
        [TestCase(3, 0)]
        [TestCase(4, 1)]
        [TestCase(5, 1)]
        [TestCase(6, 2)]
        public void CacheGet_UserDefinedKeyType_RequestIsRoutedToPrimaryNode(int key, int gridIdx)
        {
            // TODO: Use MyKey
            var res = _cache.Get(key);

            Assert.AreEqual(key, res);
            Assert.AreEqual(gridIdx, GetClientRequestGridIndex());
        }

        private sealed class MyKey
        {
            private readonly int _i;
            private readonly string _s;

            public MyKey(int i, string s)
            {
                _i = i;
                _s = s;
            }

            public int I
            {
                get { return _i; }
            }

            public string S
            {
                get { return _s; }
            }

            private bool Equals(MyKey other)
            {
                return _i == other._i && string.Equals(_s, other._s);
            }

            public override bool Equals(object obj)
            {
                if (ReferenceEquals(null, obj)) return false;
                if (ReferenceEquals(this, obj)) return true;
                if (obj.GetType() != GetType()) return false;
                return Equals((MyKey) obj);
            }

            public override int GetHashCode()
            {
                unchecked
                {
                    return (_i * 397) ^ (_s != null ? _s.GetHashCode() : 0);
                }
            }
        }
    }
}
