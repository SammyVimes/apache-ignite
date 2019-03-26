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

namespace Apache.Ignite.Core.Impl.Client.Cache
{
    using System;
    using System.Collections.Generic;
    using System.Diagnostics;

    /// <summary>
    /// Partition map for a cache.
    /// </summary>
    internal class ClientCachePartitionMap
    {
        /** Cache id. */
        private readonly int _cacheId;

        /** Array of node id per partition. */
        private readonly IList<Guid> _partitionNodeIds;

        /** Key configuration. */
        private readonly IList<ClientCacheKeyConfiguration> _keyConfiguration;

        public ClientCachePartitionMap(int cacheId, IList<Guid> partitionNodeIds,
            IList<ClientCacheKeyConfiguration> keyConfiguration = null)
        {
            Debug.Assert(partitionNodeIds != null && partitionNodeIds.Count > 0);

            _cacheId = cacheId;
            _keyConfiguration = keyConfiguration;
            _partitionNodeIds = partitionNodeIds;
        }

        public int CacheId
        {
            get { return _cacheId; }
        }

        public IList<ClientCacheKeyConfiguration> KeyConfiguration
        {
            get { return _keyConfiguration; }
        }

        public IList<Guid> PartitionNodeIds
        {
            get { return _partitionNodeIds; }
        }
    }
}
