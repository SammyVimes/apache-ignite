package org.apache.ignite.internal.processors.hadoop.impl;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Field;
import java.util.Random;
import org.apache.hadoop.examples.RandomTextWriter;
import org.apache.hadoop.examples.WordMean;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.util.Tool;
import org.apache.ignite.IgniteException;

/**
 *
 */
public class HadoopWordMeanExampleTest extends HadoopGenericExampleTest {
    /**
     * @return Extracts "words" array from class RandomTextWriter.
     */
    public static String[] getWords() {
        try {
            Field wordsField = RandomTextWriter.class.getDeclaredField("words");

            wordsField.setAccessible(true);

            return (String[])wordsField.get(null);
        }
        catch (Throwable t) {
            throw new IgniteException(t);
        }
    }

    /**
     * @param random The random.
     * @param noWords The number of words.
     * @param os The stream.
     * @throws IOException On error.
     */
    public static void generateSentence(Random random, int noWords, OutputStream os) throws IOException {
        String[] words = getWords();

        try (Writer w = new OutputStreamWriter(os)) {
            String space = " ";

            for (int i = 0; i < noWords; ++i) {
                w.write(words[random.nextInt(words.length)]);

                w.write(space);
            }
        }
    }

    /** */
    private final GenericHadoopExample ex = new GenericHadoopExample() {
        private final WordMean impl = new WordMean();

        private final Random random = new Random(0L);

        private String inDir(FrameworkParameters fp) {
            return fp.getWorkDir(name()) + "/in";
        }

        /** {@inheritDoc} */
        @Override void prepare(JobConf conf, FrameworkParameters params) throws IOException {
            // We cannot directly use Hadoop's RandomTextWriter since it is really random, but here
            // we need definitely reproducible input data.
            try (FileSystem fs = FileSystem.get(conf)) {
                try (OutputStream os = fs.create(new Path(inDir(params) + "/in-00"), true)) {
                    generateSentence(random, 2000, os);
                }
            }
        }

        /** {@inheritDoc} */
        @Override String[] parameters(FrameworkParameters fp) {
            // wordmean <in> <out>
            return new String[] {
                inDir(fp),
                fp.getWorkDir(name()) + "/out" };
        }

        /** {@inheritDoc} */
        @Override Tool tool() {
            return impl;
        }

        /** {@inheritDoc} */
        @Override void verify(String[] parameters) {
            assertEquals(9.588, impl.getMean(), 1e-3);
        }
    };

    /** {@inheritDoc} */
    @Override protected GenericHadoopExample example() {
        return ex;
    }
}
