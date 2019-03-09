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

namespace Apache.Ignite.Core.Impl.Client
{
    using System;
    using Apache.Ignite.Core.Client;
    using Apache.Ignite.Core.Impl.Binary;
    using Apache.Ignite.Core.Impl.Binary.IO;

    internal interface IClientAffinitySocket : IClientSocket  // TODO: We don't need interfaces.
    {
        /// <summary>
        /// Performs a send-receive operation with affinity awareness.
        /// </summary>
        T DoOutInOpAffinity<T, TKey>(
            ClientOp opId,
            Action<IBinaryStream> writeAction,
            Func<IBinaryStream, T> readFunc,
            int cacheId,
            TKey key,
            Func<ClientStatusCode, string, T> errorFunc = null);
    }
}
