/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.index;

import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermQuery;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;

import org.neo4j.kernel.api.impl.index.bitmaps.Bitmap;
import org.neo4j.kernel.api.impl.index.partition.IndexPartition;
import org.neo4j.kernel.api.impl.index.partition.PartitionSearcher;
import org.neo4j.kernel.api.labelscan.LabelScanWriter;
import org.neo4j.kernel.api.labelscan.NodeLabelUpdate;

import static java.lang.String.format;

// todo: should use the index itself and create partitions on demand based on given node ids
public class SimpleLuceneLabelScanWriter implements LabelScanWriter
{
    private final IndexPartition partition;
    private final PartitionSearcher partitionSearcher;
    private final BitmapDocumentFormat format;

    private final List<NodeLabelUpdate> updates;
    private long currentRange;
    private final Lock heldLock;

    public SimpleLuceneLabelScanWriter( IndexPartition partition, BitmapDocumentFormat format, Lock heldLock )
    {
        this.partition = partition;
        this.partitionSearcher = acquireSearcher( partition );
        this.format = format;
        this.heldLock = heldLock;
        currentRange = -1;
        updates = new ArrayList<>( format.bitmapFormat().rangeSize() );
    }

    @Override
    public void write( NodeLabelUpdate update ) throws IOException
    {
        long range = format.bitmapFormat().rangeOf( update.getNodeId() );

        if ( range != currentRange )
        {
            if ( range < currentRange )
            {
                throw new IllegalArgumentException( format( "NodeLabelUpdates must be supplied in order of " +
                        "ascending node id. Current range:%d, node id of this update:%d",
                        currentRange, update.getNodeId() ) );
            }

            flush();
            currentRange = range;
        }

        updates.add( update );
    }

    @Override
    public void close() throws IOException
    {
        try
        {
            flush();
        }
        finally
        {
            try
            {
                partitionSearcher.close();
                partition.maybeRefreshBlocking();
            }
            finally
            {
                heldLock.unlock();
            }
        }
    }

    private Map<Long/*range*/, Bitmap> readLabelBitMapsInRange( IndexSearcher searcher, long range ) throws IOException
    {
        Map<Long/*label*/,Bitmap> fields = new HashMap<>();
        Term documentTerm = format.rangeTerm( range );
        TermQuery query = new TermQuery( documentTerm );
        FirstHitCollector hitCollector = new FirstHitCollector();
        searcher.search( query, hitCollector );
        if ( hitCollector.hasMatched() )
        {
            Document document = searcher.doc( hitCollector.getMatchedDoc() );
            for ( IndexableField field : document.getFields() )
            {
                if ( !format.isRangeOrLabelField( field ) )
                {
                    Long label = Long.valueOf( field.name() );
                    fields.put( label, format.readBitmap( field ) );
                }
            }
        }
        return fields;
    }

    private void flush() throws IOException
    {
        if ( currentRange < 0 )
        {
            return;
        }

        IndexSearcher searcher = partitionSearcher.getIndexSearcher();
        Map<Long/*label*/,Bitmap> fields = readLabelBitMapsInRange( searcher, currentRange );
        updateFields( updates, fields );

        Document document = new Document();
        format.addRangeValuesField( document, currentRange );

        for ( Map.Entry<Long/*label*/,Bitmap> field : fields.entrySet() )
        {
            // one field per label
            Bitmap value = field.getValue();
            if ( value.hasContent() )
            {
                format.addLabelAndSearchFields( document, field.getKey(), value );
            }
        }

        if ( isEmpty( document ) )
        {
            partition.getIndexWriter().deleteDocuments( format.rangeTerm( document ) );
        }
        else
        {
            partition.getIndexWriter().updateDocument( format.rangeTerm( document ), document );
        }
        updates.clear();
    }

    private boolean isEmpty( Document document )
    {
        for ( IndexableField fieldable : document.getFields() )
        {
            if ( !format.isRangeOrLabelField( fieldable ) )
            {
                return false;
            }
        }
        return true;
    }

    private void updateFields( Iterable<NodeLabelUpdate> updates, Map<Long/*label*/, Bitmap> fields )
    {
        for ( NodeLabelUpdate update : updates )
        {
            clearLabels( fields, update );
            setLabels( fields, update );
        }
    }

    private void clearLabels( Map<Long, Bitmap> fields, NodeLabelUpdate update )
    {
        for ( Bitmap bitmap : fields.values() )
        {
            format.bitmapFormat().set( bitmap, update.getNodeId(), false );
        }
    }

    private void setLabels( Map<Long, Bitmap> fields, NodeLabelUpdate update )
    {
        for ( long label : update.getLabelsAfter() )
        {
            Bitmap bitmap = fields.get( label );
            if ( bitmap == null )
            {
                fields.put( label, bitmap = new Bitmap() );
            }
            format.bitmapFormat().set( bitmap, update.getNodeId(), true );
        }
    }

    /**
     * Acquires {@link PartitionSearcher} for the given {@link IndexPartition}
     * converting {@link IOException} to {@link UncheckedIOException} if needed.
     *
     * This method is called only once per writer to not disturb lucene's {@link SearcherManager}
     * on every {@link #flush() flush} call.
     */
    private static PartitionSearcher acquireSearcher( IndexPartition partition )
    {
        try
        {
            return partition.acquireSearcher();
        }
        catch ( IOException e )
        {
            throw new UncheckedIOException( e );
        }
    }
}
