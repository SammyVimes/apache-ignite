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

#include <fstream>
#include <climits>
#include <random>
#include <thread>
#include <condition_variable>

#include <ignite/binary/binary.h>
#include <ignite/thin/ignite_client_configuration.h>
#include <ignite/thin/ignite_client.h>

using namespace ignite::thin;

class Semaphore
{
public:
    Semaphore(uint64_t init = 0) :
        count(init)
    {
        // No-op.
    }

    void Post()
    {
        std::lock_guard<std::mutex> lock(mutex);
        ++count;
        condition.notify_one();
    }

    void Wait()
    {
        std::unique_lock<std::mutex> lock(mutex);

        while(!count)
            condition.wait(lock);

        --count;
    }

private:
    std::mutex mutex;
    std::condition_variable condition;
    uint64_t count = 0;
};

class SampleValue
{
    friend struct ignite::binary::BinaryType<SampleValue>;
public:
    SampleValue() :
        id(0)
    {
        // No-op.
    }

    SampleValue(int32_t id) :
        id(id)
    {
        // No-op.
    }

private:
    int32_t id;
};

namespace ignite
{
    namespace binary
    {
        template<>
        struct BinaryType<SampleValue>
        {
            IGNITE_BINARY_GET_TYPE_ID_AS_HASH(SampleValue)
            IGNITE_BINARY_GET_TYPE_NAME_AS_IS(SampleValue)
            IGNITE_BINARY_GET_FIELD_ID_AS_HASH
            IGNITE_BINARY_IS_NULL_FALSE(SampleValue)
            IGNITE_BINARY_GET_NULL_DEFAULT_CTOR(SampleValue)

            static void Write(BinaryWriter& writer, const SampleValue& obj)
            {
                BinaryRawWriter raw = writer.RawWriter();

                raw.WriteInt32(obj.id);
            }

            static void Read(BinaryReader& reader, SampleValue& dst)
            {
                BinaryRawReader raw = reader.RawReader();

                dst.id = raw.ReadInt32();
            }
        };
    }
}

struct BenchmarkConfiguration
{
    BenchmarkConfiguration() :
        threadNum(1),
        iterationsNum(100000),
        warmupIterationsNum(100000),
        log(0)
    {
        // No-op.
    }

    int32_t threadNum;
    int32_t iterationsNum;
    int32_t warmupIterationsNum;
    std::ostream* log;
};

class BenchmarkBase
{
public:
    BenchmarkBase(const BenchmarkConfiguration& cfg):
        cfg(cfg)
    {
        // No-op.
    }

    virtual ~BenchmarkBase()
    {
        // No-op.
    }

    virtual void SetUp() = 0;

    virtual bool Test() = 0;

    virtual void TearDown() = 0;

    virtual std::string GetName() = 0;

    const BenchmarkConfiguration& GetConfig() const
    {
        return cfg;
    }

    void GenerateRandomSequence(std::vector<int32_t>& res, int32_t num, int32_t min = 0, int32_t max = INT32_MAX)
    {
        res.clear();
        res.reserve(num);

        std::random_device seed_gen;
        std::mt19937 gen(seed_gen());
        std::uniform_int_distribution<int32_t> dist(min, max);

        for (int32_t i = 0; i < num; ++i)
            res.push_back(dist(gen));
    }

    void FillCache(cache::CacheClient<int32_t, SampleValue>& cache, int32_t min, int32_t max)
    {
        std::random_device seed_gen;
        std::mt19937 gen(seed_gen());
        std::uniform_int_distribution<int32_t> dist;

        for (int32_t i = min; i < max; ++i)
            cache.Put(i, SampleValue(dist(gen)));
    }

protected:
    const BenchmarkConfiguration cfg;
};

class ClientCacheBenchmarkAdapter : public BenchmarkBase
{
public:
    ClientCacheBenchmarkAdapter(
        const BenchmarkConfiguration& cfg,
        const IgniteClientConfiguration& clientCfg,
        const std::string& cacheName) :
        BenchmarkBase(cfg),
        client(IgniteClient::Start(clientCfg)),
        cache(client.GetOrCreateCache<int32_t, SampleValue>(cacheName.c_str()))
    {
        // No-op.
    }

    virtual ~ClientCacheBenchmarkAdapter()
    {
        // No-op.
    }

    virtual void SetUp()
    {
        cache.RemoveAll();
        cache.Clear();
    }

    virtual void TearDown()
    {
        cache.RemoveAll();
        cache.Clear();
    }

protected:
    IgniteClient client;

    cache::CacheClient<int32_t, SampleValue> cache;
};

class ClientCachePutBenchmark : public ClientCacheBenchmarkAdapter
{
public:
    ClientCachePutBenchmark(const BenchmarkConfiguration& cfg, const IgniteClientConfiguration& clientCfg) :
        ClientCacheBenchmarkAdapter(cfg, clientCfg, "PutBenchTestCache"),
        iteration(0)
    {
        // No-op.
    }

    virtual ~ClientCachePutBenchmark()
    {
        // No-op.
    }

    virtual void SetUp()
    {
        ClientCacheBenchmarkAdapter::SetUp();

        GenerateRandomSequence(keys, GetConfig().iterationsNum);
        GenerateRandomSequence(values, GetConfig().iterationsNum);
    }

    virtual void TearDown()
    {
        ClientCacheBenchmarkAdapter::TearDown();
    }

    virtual bool Test()
    {
        cache.Put(keys[iteration], SampleValue(values[iteration]));

        ++iteration;

        return iteration < GetConfig().iterationsNum;
    }

    virtual std::string GetName()
    {
        return "Thin client Put";
    }

private:
    std::vector<int32_t> keys;
    std::vector<int32_t> values;
    int32_t iteration;
};

class ClientCacheGetBenchmark : public ClientCacheBenchmarkAdapter
{
public:
    ClientCacheGetBenchmark(const BenchmarkConfiguration& cfg, const IgniteClientConfiguration& clientCfg) :
        ClientCacheBenchmarkAdapter(cfg, clientCfg, "GutBenchTestCache"),
        iteration(0)
    {
        // No-op.
    }

    virtual ~ClientCacheGetBenchmark()
    {
        // No-op.
    }

    virtual void SetUp()
    {
        ClientCacheBenchmarkAdapter::SetUp();

        GenerateRandomSequence(keys, GetConfig().iterationsNum, 0, 10000);

        FillCache(cache, 0, 10000);
    }

    virtual void TearDown()
    {
        ClientCacheBenchmarkAdapter::TearDown();
    }

    virtual bool Test()
    {
        SampleValue val;
        cache.Get(keys[iteration], val);

        ++iteration;

        return iteration < GetConfig().iterationsNum;
    }

    virtual std::string GetName()
    {
        return "Thin client Get";
    }

private:
    std::vector<int32_t> keys;
    int32_t iteration;
};


void PrintBackets(const std::string& annotation, std::vector<int64_t>& res, std::ostream& log)
{
    std::sort(res.begin(), res.end());

    log << annotation << ": "
        << "min: " << res.front() << "us, "
        << "10%: " << res.at(static_cast<size_t>(res.size() * 0.1)) << "us, "
        << "20%: " << res.at(static_cast<size_t>(res.size() * 0.2)) << "us, "
        << "50%: " << res.at(static_cast<size_t>(res.size() * 0.5)) << "us, "
        << "90%: " << res.at(static_cast<size_t>(res.size() * 0.9)) << "us, "
        << "95%: " << res.at(static_cast<size_t>(res.size() * 0.95)) << "us, "
        << "99%: " << res.at(static_cast<size_t>(res.size() * 0.99)) << "us, "
        << "max: " << res.back() << "us"
        << std::endl;
}

void MeasureThread(Semaphore& sem, std::vector<int64_t>& latency, BenchmarkBase& bench)
{
    using namespace std::chrono;

    sem.Wait();

    latency.clear();

    latency.reserve(bench.GetConfig().iterationsNum);

    auto begin = steady_clock::now();

    bool run = true;

    while (run)
    {
        auto putBegin = steady_clock::now();

        run = bench.Test();

        latency.push_back(duration_cast<microseconds>(steady_clock::now() - putBegin).count());
    }
}

template<typename T>
int64_t MeasureInThreads(
    const BenchmarkConfiguration& cfg,
    const IgniteClientConfiguration& clientCfg,
    std::vector<int64_t>& latency)
{
    std::vector<T> contexts;
    std::vector<std::thread> threads;
    std::vector< std::vector<int64_t> > latencies(cfg.threadNum);

    contexts.reserve(cfg.threadNum);
    threads.reserve(cfg.threadNum);

    Semaphore sem(0);

    for (int32_t i = 0; i < cfg.threadNum; ++i)
    {
        contexts.push_back(T(cfg, clientCfg));

        contexts[i].SetUp();

        threads.push_back(std::thread(MeasureThread, std::ref(sem), std::ref(latencies[i]), std::ref(contexts[i])));
    }

    using namespace std::chrono;

    auto begin = steady_clock::now();

    for (int32_t i = 0; i < cfg.threadNum; ++i)
        sem.Post();

    for (int32_t i = 0; i < cfg.threadNum; ++i)
        threads[i].join();

    for (int32_t i = 0; i < cfg.threadNum; ++i)
        contexts[i].TearDown();

    auto duration = steady_clock::now() - begin;

    latency.clear();
    latency.reserve(cfg.iterationsNum * cfg.threadNum);

    for (int32_t i = 0; i < cfg.threadNum; ++i)
        latency.insert(latency.end(), latencies[i].begin(), latencies[i].end());

    return duration_cast<milliseconds>(duration).count();
}

template<typename T>
void Run(const std::string& annotation, const BenchmarkConfiguration& cfg, const IgniteClientConfiguration& clientCfg)
{
    std::ostream* log = cfg.log;

    if (log)
        *log << "Warming up. Operations number: " << cfg.warmupIterationsNum << std::endl;

    std::vector<int64_t> latency;
    int64_t duration = MeasureInThreads<T>(cfg, clientCfg, latency);

    if (log)
        *log << std::endl << "Starting benchmark. Operations number: " << cfg.iterationsNum << std::endl;

    duration = MeasureInThreads<T>(cfg, clientCfg, latency);

    if (log)
    {
        PrintBackets(annotation, latency, *log);

        *log << std::endl << "Duration: " << duration << "ms" << std::endl;

        *log << "Throughput: " << static_cast<int32_t>((cfg.iterationsNum * 1000.0) / duration) << "op/sec" << std::endl;
    }
}

const int32_t WARMUP_ITERATIONS_NUM = 100000;
const int32_t ITERATIONS_NUM = 1000000;
const int32_t THREAD_NUM = 1;

const std::string address = "127.0.0.1:11110";

void PrintHelp(const char* bin)
{
    std::cout << "Usage: " << bin << " <command>" << std::endl;
    std::cout << "Possible commands:" << std::endl;
    std::cout << " help   : Show this message" << std::endl;
    std::cout << " get    : Run 'get' benchmark" << std::endl;
    std::cout << " put    : Run 'put' benchmark" << std::endl;
    std::cout << std::endl;
}

int main(int argc, char* argv[])
{
    const char* binName = argv[0];

    if (argc != 1)
    {
        PrintHelp(binName);

        return -1;
    }

    BenchmarkConfiguration cfg;
    cfg.iterationsNum = ITERATIONS_NUM;
    cfg.warmupIterationsNum = WARMUP_ITERATIONS_NUM;
    cfg.threadNum = THREAD_NUM;
    cfg.log = &std::cout;

    IgniteClientConfiguration clientCfg;
    clientCfg.SetEndPoints(address);

    Run<ClientCacheGetBenchmark>("Get", cfg, clientCfg);

    std::string cmd(argv[1]);

    if (cmd == "get")
        Run<ClientCacheGetBenchmark>("Get", cfg, clientCfg);
    else if (cmd == "put")
        Run<ClientCachePutBenchmark>("Put", cfg, clientCfg);
    else
    {
        PrintHelp(binName);

        return cmd == "help" ? 0 : -1;
    }

    return 0;
}