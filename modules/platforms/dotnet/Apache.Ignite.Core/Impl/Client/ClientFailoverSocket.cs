﻿/*
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
    using System.Collections.Generic;
    using System.Diagnostics;
    using System.Diagnostics.CodeAnalysis;
    using System.Linq;
    using System.Net;
    using System.Net.Sockets;
    using System.Threading;
    using System.Threading.Tasks;
    using Apache.Ignite.Core.Cache.Affinity;
    using Apache.Ignite.Core.Client;
    using Apache.Ignite.Core.Impl.Binary;
    using Apache.Ignite.Core.Impl.Binary.IO;
    using Apache.Ignite.Core.Impl.Client.Cache;

    /// <summary>
    /// Socket wrapper with reconnect/failover functionality: reconnects on failure.
    /// </summary>
    internal class ClientFailoverSocket : IClientSocket, IClientAffinitySocket
    {
        /** Underlying socket. */
        private ClientSocket _socket;

        /** Current global endpoint index for Round-robin. */
        private static long _endPointIndex;

        /** Config. */
        private readonly IgniteClientConfiguration _config;

        /** Marshaller. */
        private readonly Marshaller _marsh;

        /** Endpoints with corresponding hosts. */
        private readonly List<KeyValuePair<IPEndPoint, string>> _endPoints;

        /** Locker. */
        private readonly object _syncRoot = new object();

        /** Disposed flag. */
        private bool _disposed;

        /** Current affinity topology version. */
        private AffinityTopologyVersion? _affinityTopologyVersion;

        /** Map from node ID to connected socket. */
        private Dictionary<Guid, ClientSocket> _nodeSocketMap = new Dictionary<Guid, ClientSocket>(); // TODO: Populate

        /** Map from cache ID to partition mapping. */
        private volatile ClientCacheTopologyPartitionMap _cachePartitionMap;

        /// <summary>
        /// Initializes a new instance of the <see cref="ClientFailoverSocket"/> class.
        /// </summary>
        /// <param name="config">The configuration.</param>
        /// <param name="marsh"></param>
        public ClientFailoverSocket(IgniteClientConfiguration config, Marshaller marsh)
        {
            Debug.Assert(config != null);
            Debug.Assert(marsh != null);

            _config = config;
            _marsh = marsh;

#pragma warning disable 618 // Type or member is obsolete
            if (config.Host == null && (config.Endpoints == null || config.Endpoints.Count == 0))
            {
                throw new IgniteClientException("Invalid IgniteClientConfiguration: Host is null, " +
                                                "Endpoints is null or empty. Nowhere to connect.");
            }
#pragma warning restore 618

            _endPoints = GetIpEndPoints(config).ToList();

            if (_endPoints.Count == 0)
            {
                throw new IgniteClientException("Failed to resolve all specified hosts.");
            }

            // TODO: Connect only one and return quicker?
            Connect();
        }

        /** <inheritdoc /> */
        public T DoOutInOp<T>(ClientOp opId, Action<IBinaryStream> writeAction, Func<IBinaryStream, T> readFunc,
            Func<ClientStatusCode, string, T> errorFunc = null)
        {
            return GetSocket().DoOutInOp(opId, writeAction, readFunc, errorFunc);
        }

        /** <inheritdoc /> */
        public T DoOutInOpAffinity<T, TKey>( // TODO: This does not belong in Socket class. Move to IgniteClient?
            ClientOp opId,
            Action<BinaryWriter> writeAction,
            Func<IBinaryStream, T> readFunc,
            int cacheId,
            TKey key,
            Func<ClientStatusCode, string, T> errorFunc = null)
        {
            UpdatePartitionMapping(cacheId);

            var partMap = _cachePartitionMap;
            ClientCachePartitionMap cachePartMap;
            if (partMap != null &&
                partMap.AffinityTopologyVersion == _affinityTopologyVersion &&
                partMap.CachePartitionMap.TryGetValue(cacheId, out cachePartMap))
            {
                // TODO: Extract affinity key and hash code
                // 1) Extract serialization logic from ClientSocket (WriteMessage) so we can serialize before selecting the Socket
                // 2) Add optional callback to BinaryWriter so we can extract both AffinityKey (by field id) and hash code (in case it's a BinaryObject)
                var partition = GetPartition(key);
                var nodeId = cachePartMap.PartitionNodeIds[partition];

                ClientSocket socket;
                if (_nodeSocketMap.TryGetValue(nodeId, out socket))
                {
                    return socket.DoOutInOp(opId, s =>
                    {
                        var writer = _marsh.StartMarshal(s);
                        writeAction(writer);
                        _marsh.FinishMarshal(writer);
                    }, readFunc, errorFunc);
                }
            }

            // TODO: Request partition map if it is out of date or unknown
            // TODO: Calculate target node for given cache and given key.
            // TODO: Move the method to IClientAffinitySocket or something like that?
            return GetSocket().DoOutInOp(opId, s =>
            {
                var writer = _marsh.StartMarshal(s);
                writeAction(writer);
                _marsh.FinishMarshal(writer);
            }, readFunc, errorFunc);
        }

        /** <inheritdoc /> */
        public Task<T> DoOutInOpAsync<T>(ClientOp opId, Action<IBinaryStream> writeAction, Func<IBinaryStream, T> readFunc, Func<ClientStatusCode, string, T> errorFunc = null)
        {
            return GetSocket().DoOutInOpAsync(opId, writeAction, readFunc, errorFunc);
        }

        /** <inheritdoc /> */
        public ClientProtocolVersion ServerVersion
        {
            get { return GetSocket().ServerVersion; }
        }

        /** <inheritdoc /> */
        public EndPoint RemoteEndPoint
        {
            get
            {
                lock (_syncRoot)
                {
                    return _socket != null ? _socket.RemoteEndPoint : null;
                }
            }
        }

        /** <inheritdoc /> */
        public EndPoint LocalEndPoint
        {
            get
            {
                lock (_syncRoot)
                {
                    return _socket != null ? _socket.LocalEndPoint : null;
                }
            }
        }

        /// <summary>
        /// Checks the disposed state.
        /// </summary>
        private ClientSocket GetSocket()
        {
            lock (_syncRoot)
            {
                if (_disposed)
                {
                    throw new ObjectDisposedException("ClientFailoverSocket");
                }

                if (_socket == null)
                {
                    Connect();
                }

                return _socket;
            }
        }

        /** <inheritdoc /> */
        [SuppressMessage("Microsoft.Usage", "CA1816:CallGCSuppressFinalizeCorrectly",
            Justification = "There is no finalizer.")]
        public void Dispose()
        {
            lock (_syncRoot)
            {
                _disposed = true;

                if (_socket != null)
                {
                    _socket.Dispose();
                    _socket = null;
                }
            }
        }

        /// <summary>
        /// Connects the socket.
        /// </summary>
        private void Connect()
        {
            // TODO: Connect to all endpoints.
            // We need a CurrentSocket property that is round-robin.
            // Other connections can be established in the background.
            List<Exception> errors = null;
            var startIdx = (int) Interlocked.Increment(ref _endPointIndex);

            for (var i = 0; i < _endPoints.Count; i++)
            {
                var idx = (startIdx + i) % _endPoints.Count;
                var endPoint = _endPoints[idx];

                try
                {
                    _socket = new ClientSocket(_config, endPoint.Key, endPoint.Value, OnSocketError, null,
                        OnAffinityTopologyVersionChange);

                    return;
                }
                catch (SocketException e)
                {
                    if (errors == null)
                    {
                        errors = new List<Exception>();
                    }

                    errors.Add(e);
                }
            }

            throw new AggregateException("Failed to establish Ignite thin client connection, " +
                                         "examine inner exceptions for details.", errors);
        }

        /// <summary>
        /// Called when socket error occurs.
        /// </summary>
        private void OnSocketError()
        {
            if (_config.ReconnectDisabled)
            {
                return;
            }

            // Reconnect on next operation.
            lock (_syncRoot)
            {
                _socket = null;
            }
        }

        /// <summary>
        /// Updates current Affinity Topology Version.
        /// </summary>
        private void OnAffinityTopologyVersionChange(AffinityTopologyVersion affinityTopologyVersion)
        {
            _affinityTopologyVersion = affinityTopologyVersion;
        }

        /// <summary>
        /// Gets the endpoints: all combinations of IP addresses and ports according to configuration.
        /// </summary>
        private static IEnumerable<KeyValuePair<IPEndPoint, string>> GetIpEndPoints(IgniteClientConfiguration cfg)
        {
            foreach (var e in Endpoint.GetEndpoints(cfg))
            {
                var host = e.Host;
                Debug.Assert(host != null);  // Checked by GetEndpoints.

                // GetHostEntry accepts IPs, but TryParse is a more efficient shortcut.
                IPAddress ip;

                if (IPAddress.TryParse(host, out ip))
                {
                    for (var i = 0; i <= e.PortRange; i++)
                    {
                        yield return new KeyValuePair<IPEndPoint, string>(new IPEndPoint(ip, e.Port + i), host);
                    }
                }
                else
                {
                    for (var i = 0; i <= e.PortRange; i++)
                    {
                        foreach (var x in Dns.GetHostEntry(host).AddressList)
                        {
                            yield return new KeyValuePair<IPEndPoint, string>(new IPEndPoint(x, e.Port + i), host);
                        }
                    }
                }
            }
        }

        /// <summary>
        /// Updates the partition mapping.
        /// </summary>
        private void UpdatePartitionMapping(int cacheId)
        {
            // TODO: Check if we need to update?
            // TODO: Sync and async versions to call from sync and async methods.

            var mapping = new Dictionary<int, ClientCachePartitionMap>();

            DoOutInOp<object>(ClientOp.CachePartitions, s =>
            {
                s.WriteInt(1);  // One cache.
                s.WriteInt(cacheId);
            }, s =>
            {
                var affinityTopologyVersion = new AffinityTopologyVersion(s.ReadLong(), s.ReadInt());
                var size = s.ReadInt();

                for (int i = 0; i < size; i++)
                {
                    var g = new ClientCacheAffinityAwarenessGroup(s);

                    // Count partitions to avoid reallocating array.
                    int maxPartNum = 0;
                    foreach (var partMap in g.PartitionMap)
                    {
                        foreach (var part in partMap.Value)
                        {
                            if (part > maxPartNum)
                            {
                                maxPartNum = part;
                            }
                        }
                    }

                    // Populate partition array.
                    var partNodeIds = new Guid[maxPartNum + 1];
                    foreach (var partMap in g.PartitionMap)
                    {
                        foreach (var part in partMap.Value)
                        {
                            partNodeIds[part] = partMap.Key;
                        }
                    }

                    foreach (var keyConfig in g.KeyConfigs)
                    {
                        mapping[keyConfig.CacheId] = new ClientCachePartitionMap(keyConfig, partNodeIds);
                    }
                }

                _cachePartitionMap = new ClientCacheTopologyPartitionMap(mapping, affinityTopologyVersion);

                return null;
            });

        }

        private int GetPartition<TKey>(TKey key)
        {
            // TODO: See cpp\thin-client\include\ignite\impl\thin\writable_key.h
            // We'll have to do the same - implement hash for every primitive
            // And for non-primitive we have to get it somehow from the writer BEFORE sending the request
            var keyHash = key.GetHashCode();
            var partitionCount = 0; // TODO: Use affinityMapping.size
            return ClientRendezvousAffinityFunction.GetPartitionForKey(keyHash, partitionCount);
        }
    }
}
