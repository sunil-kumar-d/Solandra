/**
 * Copyright 2009 T Jake Luciani
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package lucandra;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

import lucandra.cluster.AbstractIndexManager;
import lucandra.cluster.RedisIndexManager;

import org.apache.cassandra.config.ConfigurationException;
import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.Row;
import org.apache.cassandra.db.RowMutation;
import org.apache.cassandra.db.SliceByNamesReadCommand;
import org.apache.cassandra.db.TimestampClock;
import org.apache.cassandra.db.filter.QueryPath;
import org.apache.cassandra.service.AbstractCassandraDaemon;
import org.apache.cassandra.service.StorageProxy;
import org.apache.cassandra.service.StorageService;
import org.apache.cassandra.thrift.ConsistencyLevel;
import org.apache.cassandra.thrift.InvalidRequestException;
import org.apache.cassandra.thrift.UnavailableException;
import org.apache.log4j.Logger;
import org.apache.lucene.index.Term;
import org.jredis.connector.ConnectionSpec;
import org.jredis.ri.alphazero.JRedisService;
import org.jredis.ri.alphazero.connection.DefaultConnectionSpec;

public class CassandraUtils {

    public static final String keySpace = System.getProperty("lucandra.keyspace", "L");
    public static final String termVecColumnFamily = "TI";
    public static final String docColumnFamily = "Docs";
    public static final String metaInfoColumnFamily = "TL";

    public static final String positionVectorKey = "P";
    public static final String offsetVectorKey = "O";
    public static final String termFrequencyKey = "F";
    public static final String normsKey = "N";
    public static final byte[] positionVectorKeyBytes = positionVectorKey.getBytes();
    public static final byte[] offsetVectorKeyBytes = offsetVectorKey.getBytes();
    public static final byte[] termFrequencyKeyBytes = termFrequencyKey.getBytes();
    public static final byte[] normsKeyBytes = normsKey.getBytes();

    public static final int maxDocsPerShard = 100000;

    public static final byte[] emptyByteArray = new byte[] {};
    public static final List<Number> emptyArray = Arrays.asList(new Number[] { 0 });
    public static final String delimeter = new String("\uffff");
    public static final byte[] delimeterBytes;

    public static final String finalToken = new String("\ufffe\ufffe");
    public static final byte[] finalTokenBytes;

    public static final String documentIdField = System.getProperty("lucandra.id.field", delimeter + "KEY" + delimeter);
    public static final String documentMetaField = delimeter + "META" + delimeter;
    public static final byte[] documentMetaFieldBytes;

    public static final boolean indexHashingEnabled = Boolean.valueOf(System.getProperty("index.hashing", "true"));

    public static final JRedisService service;
    public static final AbstractIndexManager indexManager;
    public static final QueryPath metaColumnPath;
    static {

        int database = 11;
        int connCnt = 7;

        ConnectionSpec connectionSpec = DefaultConnectionSpec.newSpec(System.getProperty("redis.host", "localhost"), Integer.valueOf(System.getProperty(
                "redis.port", "6379")), database, "jredis".getBytes());

        service = new JRedisService(connectionSpec, connCnt);
        indexManager = new RedisIndexManager(service);

        try {
            delimeterBytes = delimeter.getBytes("UTF-8");
            documentMetaFieldBytes = documentMetaField.getBytes("UTF-8");
            finalTokenBytes = finalToken.getBytes("UTF-8");
            metaColumnPath = new QueryPath(CassandraUtils.docColumnFamily);
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("UTF-8 not supported by this JVM");
        }
    }

    public static final String hashChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    public static final BigInteger CHAR_MASK = new BigInteger("65535");

    private static final Logger logger = Logger.getLogger(CassandraUtils.class);

    private static boolean cassandraStarted = false;

    // Start Cassandra up!!!
    public static synchronized void startup() {

        if (cassandraStarted)
            return;

        cassandraStarted = true;

        AbstractCassandraDaemon daemon = new AbstractCassandraDaemon() {

            @Override
            public void stop() {
                // TODO Auto-generated method stub

            }

            @Override
            public void start() throws IOException {
                // TODO Auto-generated method stub

            }
        };

        try {
            daemon.init(new String[] {});
        } catch (IOException e) {

            e.printStackTrace();
            System.exit(2);
        }

        // Check for Lucandra schema
        // if not there then load from yaml
        if (DatabaseDescriptor.getTableDefinition(keySpace) == null)
            try {
                StorageService.instance.loadSchemaFromYAML();
            } catch (ConfigurationException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                System.exit(3);
            } catch (IOException e) {
                // TODO Auto-generated catch block
                e.printStackTrace();
                System.exit(4);
            }

    }

    public static byte[] createColumnName(Term term) {

        return createColumnName(term.field(), term.text());
    }

    public static byte[] createColumnName(String field, String text) {

        // case of all terms
        if (field.equals("") || text == null)
            return delimeterBytes;

        try {
            return (field + delimeter + text).getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new RuntimeException("JVM doesn't support UTF-8", e);
        }
    }

    public static Term parseTerm(String termStr) {

        int index = termStr.indexOf(delimeter);

        if (index < 0) {
            throw new RuntimeException("invalid term format: " + index + " " + termStr);
        }

        return new Term(termStr.substring(0, index), termStr.substring(index + delimeter.length()));
    }

    public static final byte[] intToByteArray(int value) {
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
    }

    public static final int byteArrayToInt(byte[] b) {
        return (b[0] << 24) + ((b[1] & 0xFF) << 16) + ((b[2] & 0xFF) << 8) + (b[3] & 0xFF);
    }

    public static final byte[] intVectorToByteArray(List<Number> intVector) {

        if (intVector.size() == 0)
            return emptyByteArray;

        if (intVector.get(0) instanceof Byte)
            return new byte[] { intVector.get(0).byteValue() };

        ByteBuffer buffer = ByteBuffer.allocate(4 * intVector.size());

        for (Number i : intVector) {
            buffer.putInt(i.intValue());
        }

        return buffer.array();
    }

    public static boolean compareByteArrays(byte[] a, byte[] b) {

        if (a.length != b.length)
            return false;

        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i])
                return false;
        }

        return true;

    }

    public static final int[] byteArrayToIntArray(byte[] b) {

        if (b.length % 4 != 0)
            throw new RuntimeException("Not a valid int array:" + b.length);

        int[] intArray = new int[b.length / 4];
        int idx = 0;

        for (int i = 0; i < b.length; i += 4) {
            intArray[idx++] = (b[i] << 24) + ((b[i + 1] & 0xFF) << 16) + ((b[i + 2] & 0xFF) << 8) + (b[i + 3] & 0xFF);
        }

        return intArray;
    }

    public static final byte[] encodeLong(long l) {
        ByteBuffer buffer = ByteBuffer.allocate(8);

        buffer.putLong(l);

        return buffer.array();
    }

    public static final long decodeLong(byte[] bytes) {

        if (bytes.length != 8)
            throw new RuntimeException("must be 8 bytes");

        ByteBuffer buffer = ByteBuffer.wrap(bytes);

        return buffer.getLong();
    }

    public static final byte[] encodeUUID(UUID docUUID) {

        ByteBuffer buffer = ByteBuffer.allocate(16);

        buffer.putLong(docUUID.getMostSignificantBits());
        buffer.putLong(docUUID.getLeastSignificantBits());
        return buffer.array();
    }

    public static final UUID readUUID(byte[] bytes) {

        if (bytes.length != 16)
            throw new RuntimeException("uuid must be exactly 16 bytes");

        return UUID.nameUUIDFromBytes(bytes);

    }

    public static void addMutations(Map<byte[], RowMutation> mutationList, String columnFamily, byte[] column, byte[] key, byte[] value,
            Map<byte[], List<Number>> superColumns) {

        // Find or create row mutation
        RowMutation rm = mutationList.get(key);
        if (rm == null) {

            rm = new RowMutation(CassandraUtils.keySpace, key);

            mutationList.put(key, rm);
        }

        if (value == null && superColumns == null) { // remove

            if (column != null) {
                rm.delete(new QueryPath(columnFamily, column), new TimestampClock(System.currentTimeMillis()));
            } else {
                rm.delete(new QueryPath(columnFamily), new TimestampClock(System.currentTimeMillis()));
            }

        } else { // insert

            if (superColumns == null) {

                rm.add(new QueryPath(columnFamily, null, column), value, new TimestampClock(System.currentTimeMillis()));

            } else {

                for (Map.Entry<byte[], List<Number>> e : superColumns.entrySet()) {
                    rm.add(new QueryPath(columnFamily, column, e.getKey()), intVectorToByteArray(e.getValue()), new TimestampClock(System.currentTimeMillis()));
                }
            }
        }
    }

    public static void robustInsert(List<RowMutation> mutations, ConsistencyLevel cl) {

        int attempts = 0;
        while (attempts++ < 10) {

            try {
                StorageProxy.mutate(mutations, cl);
                return;
            } catch (UnavailableException e) {

            } catch (TimeoutException e) {

            }

            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {

            }
        }

        throw new RuntimeException("insert failed after 10 attempts");
    }

    public static List<Row> robustGet(List<ReadCommand> rc, ConsistencyLevel cl) {
        List<Row> rows = null;
        int attempts = 0;
        while (attempts++ < 10) {
            try {
                rows = StorageProxy.readProtocol(rc, cl);
                break;
            } catch (IOException e1) {
                throw new RuntimeException(e1);
            } catch (UnavailableException e1) {

            } catch (TimeoutException e1) {

            } catch (InvalidRequestException e) {
                throw new RuntimeException(e);
            }

            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {

            }
        }

        if (attempts >= 10)
            throw new RuntimeException("Read command failed after 10 attempts");

        return rows;
    }

    public static List<Row> robustGet(byte[] key, QueryPath qp, List<byte[]> columns, ConsistencyLevel cl) {

        ReadCommand rc = new SliceByNamesReadCommand(CassandraUtils.keySpace, key, qp, columns);

        return robustGet(Arrays.asList(rc), cl);

    }

    /** Read the object from bytes string. */
    public static Object fromBytes(byte[] data) throws IOException, ClassNotFoundException {

        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data));
        Object o = ois.readObject();
        ois.close();
        return o;
    }

    /** Write the object to bytes. */
    public static byte[] toBytes(Object o) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(o);
        oos.close();
        return baos.toByteArray();
    }

    public static byte[] hashBytes(byte[] key) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("SHA");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }

        int saltSize = key.length; // Only hash the indexName

        byte[] salt = new byte[saltSize];
        System.arraycopy(key, 0, salt, 0, key.length);

        md.update(salt);

        return bytesForBig(new BigInteger(1, md.digest()), 8);
    }

    public static byte[] hashKeyBytes(byte[]... keys) {
        byte hashedKey[] = null;

        if (keys.length <= 1 || !Arrays.equals(keys[keys.length - 2], delimeterBytes))
            throw new IllegalStateException("malformed key");

        byte[] indexName = keys[0];

        if (indexHashingEnabled) {
            int delimiterCount = 1;
            for (int i = 0; i < keys.length - 2; i++) {

                if (Arrays.equals(keys[i], delimeterBytes)) {
                    delimiterCount++;
                }
            }

            if (delimiterCount > 2)
                throw new IllegalStateException("key contains too many delimiters");

            indexName = hashBytes(indexName);
        }
        // no hashing, just combine the arrays together

        int totalBytes = indexName.length;
        for (int i = 1; i < keys.length; i++)
            totalBytes += keys[i].length;

        hashedKey = new byte[totalBytes];
        System.arraycopy(indexName, 0, hashedKey, 0, indexName.length);
        int currentLen = indexName.length;

        for (int i = 1; i < keys.length; i++) {
            System.arraycopy(keys[i], 0, hashedKey, currentLen, keys[i].length);
            currentLen += keys[i].length;
        }

        return hashedKey;
    }

    // based on cassandra source
    private static byte[] bytesForBig(BigInteger big, int sigchars) {
        byte[] chars = new byte[sigchars];

        for (int i = 0; i < sigchars; i++) {
            int maskpos = 16 * (sigchars - (i + 1));
            // apply bitmask and get char value
            chars[i] = (byte) hashChars.charAt(big.and(CHAR_MASK.shiftLeft(maskpos)).shiftRight(maskpos).intValue() % hashChars.length());
        }

        return chars;
    }

    public static int readVInt(byte[] buf) {
        int length = buf.length;

        byte b = buf[0];
        int i = b & 0x7F;
        for (int pos = 1, shift = 7; (b & 0x80) != 0 && pos < length; shift += 7, pos++) {
            b = buf[pos];
            i |= (b & 0x7F) << shift;
        }

        return i;
    }

    public static byte[] writeVInt(int i) {
        int length = 0;
        int p = i;

        while ((p & ~0x7F) != 0) {
            p >>>= 7;
            length++;
        }
        length++;

        byte[] buf = new byte[length];
        int pos = 0;
        while ((i & ~0x7F) != 0) {
            buf[pos] = ((byte) ((i & 0x7f) | 0x80));
            i >>>= 7;
            pos++;
        }
        buf[pos] = (byte) i;

        return buf;
    }
}
