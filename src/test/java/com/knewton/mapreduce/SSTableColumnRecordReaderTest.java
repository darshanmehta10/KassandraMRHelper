/**
 * Copyright 2013, 2014, 2015 Knewton
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *
 */
package com.knewton.mapreduce;

import com.knewton.mapreduce.constant.PropertyConstants;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.db.BufferCounterCell;
import org.apache.cassandra.db.BufferDecoratedKey;
import org.apache.cassandra.db.ColumnSerializer.Flag;
import org.apache.cassandra.db.CounterCell;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.composites.CellName;
import org.apache.cassandra.db.composites.CellNameType;
import org.apache.cassandra.db.composites.SimpleDenseCellNameType;
import org.apache.cassandra.db.context.CounterContext;
import org.apache.cassandra.db.marshal.BytesType;
import org.apache.cassandra.db.marshal.LongType;
import org.apache.cassandra.dht.IPartitioner;
import org.apache.cassandra.dht.OrderPreservingPartitioner.StringToken;
import org.apache.cassandra.dht.RandomPartitioner;
import org.apache.cassandra.io.sstable.Descriptor;
import org.apache.cassandra.io.sstable.Descriptor.Type;
import org.apache.cassandra.io.sstable.ISSTableScanner;
import org.apache.cassandra.io.sstable.SSTableIdentityIterator;
import org.apache.cassandra.io.sstable.format.SSTableReader;
import org.apache.cassandra.utils.CounterId;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.lib.input.FileSplit;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.runners.MockitoJUnitRunner;

import java.io.File;
import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * This tests {@link SSTableColumnRecordReader}
 *
 * @author Giannis Neokleous
 *
 */
@RunWith(MockitoJUnitRunner.class)
public class SSTableColumnRecordReaderTest {
    private static final String TABLE_PATH_STR = "keyspace/columnFamily";

    @Mock
    private SSTableReader ssTableReader;

    @Mock
    private ISSTableScanner tableScanner;

    @Spy
    private SSTableColumnRecordReader ssTableColumnRecordReader;

    private DecoratedKey key;
    private CounterCell value;
    private FileSplit inputSplit;
    private TaskAttemptID attemptId;
    private Configuration conf;

    @Before
    public void setUp() throws Exception {
        conf = new Configuration();
        attemptId = new TaskAttemptID();
        Path inputPath = new Path(TABLE_PATH_STR);
        inputSplit = new FileSplit(inputPath, 0, 1, null);
        Descriptor desc = new Descriptor(new File(TABLE_PATH_STR), "keyspace", "columnFamily", 1,
                                         Type.FINAL);

        doReturn(desc).when(ssTableColumnRecordReader).getDescriptor();

        doNothing().when(ssTableColumnRecordReader).copyTablesToLocal(any(FileSystem.class),
                                                                      any(FileSystem.class),
                                                                      any(Path.class),
                                                                      any(TaskAttemptContext.class));

        doReturn(ssTableReader).when(ssTableColumnRecordReader)
            .openSSTableReader(any(IPartitioner.class), any(CFMetaData.class));

        when(ssTableReader.estimatedKeys()).thenReturn(2L);
        when(ssTableReader.getScanner()).thenReturn(tableScanner);

        when(tableScanner.hasNext()).thenReturn(true, true, false);

        key = new BufferDecoratedKey(new StringToken("a"), ByteBuffer.wrap("b".getBytes()));
        CellNameType simpleDenseCellType = new SimpleDenseCellNameType(BytesType.instance);
        CellName cellName = simpleDenseCellType.cellFromByteBuffer(ByteBuffer.wrap("n".getBytes()));
        ByteBuffer counterBB = CounterContext.instance()
            .createGlobal(CounterId.fromInt(0), System.currentTimeMillis(), 123L);
        value = BufferCounterCell.create(cellName, counterBB, System.currentTimeMillis(), 0L,
                                         Flag.PRESERVE_SIZE);

        SSTableIdentityIterator row1 = getNewRow();
        SSTableIdentityIterator row2 = getNewRow();

        when(tableScanner.next()).thenReturn(row1, row2);
    }

    private SSTableIdentityIterator getNewRow() {
        SSTableIdentityIterator row = mock(SSTableIdentityIterator.class);
        when(row.hasNext()).thenReturn(true, true, false);
        when(row.getKey()).thenReturn(key);
        when(row.next()).thenReturn(value);
        return row;
    }

    private TaskAttemptContext getTaskAttemptContext() {
        conf.set(PropertyConstants.COLUMN_COMPARATOR.txt, LongType.class.getName());
        conf.set(PropertyConstants.PARTITIONER.txt,
                 RandomPartitioner.class.getName());
        return new TaskAttemptContextImpl(conf, attemptId);
    }

    @Test
    public void testNextKeyValue() throws Exception {
        Path inputPath = inputSplit.getPath();
        FileSystem remoteFS = FileSystem.get(inputPath.toUri(), conf);
        FileSystem localFS = FileSystem.getLocal(conf);
        TaskAttemptContext context = getTaskAttemptContext();
        ssTableColumnRecordReader.initialize(inputSplit, context);
        verify(ssTableColumnRecordReader).copyTablesToLocal(remoteFS, localFS, inputPath, context);

        assertEquals(0, ssTableColumnRecordReader.getProgress(), 0);
        assertTrue(ssTableColumnRecordReader.nextKeyValue());
        assertEquals(key.getKey(), ssTableColumnRecordReader.getCurrentKey());
        assertEquals(value, ssTableColumnRecordReader.getCurrentValue());

        assertEquals(0.5, ssTableColumnRecordReader.getProgress(), 0);
        assertTrue(ssTableColumnRecordReader.nextKeyValue());
        assertEquals(key.getKey(), ssTableColumnRecordReader.getCurrentKey());
        assertEquals(value, ssTableColumnRecordReader.getCurrentValue());

        assertEquals(1, ssTableColumnRecordReader.getProgress(), 0);
        assertFalse(ssTableColumnRecordReader.nextKeyValue());
        assertNull(ssTableColumnRecordReader.getCurrentKey());
        assertNull(ssTableColumnRecordReader.getCurrentValue());
    }

    @After
    public void tearDown() throws Exception {
        ssTableColumnRecordReader.close();
    }

}
