package com.navercorp.pinpoint.web.dao.hbase;

import com.navercorp.pinpoint.common.hbase.HbaseColumnFamily;
import com.navercorp.pinpoint.common.hbase.HbaseOperations2;
import com.navercorp.pinpoint.common.hbase.rowmapper.RequestAwareDynamicRowMapper;
import com.navercorp.pinpoint.common.hbase.rowmapper.RequestAwareRowMapper;
import com.navercorp.pinpoint.common.hbase.rowmapper.RequestAwareRowMapperAdaptor;
import com.navercorp.pinpoint.common.hbase.RowMapper;
import com.navercorp.pinpoint.common.server.bo.SpanBo;
import com.navercorp.pinpoint.common.server.bo.serializer.RowKeyDecoder;
import com.navercorp.pinpoint.common.server.bo.serializer.RowKeyEncoder;
import com.navercorp.pinpoint.common.server.bo.serializer.trace.v2.SpanDecoder;
import com.navercorp.pinpoint.common.server.bo.serializer.trace.v2.SpanDecoderV0;
import com.navercorp.pinpoint.common.server.bo.serializer.trace.v2.SpanEncoder;
import com.navercorp.pinpoint.common.util.Assert;
import com.navercorp.pinpoint.common.util.TransactionId;
import com.navercorp.pinpoint.web.dao.TraceDao;
import com.navercorp.pinpoint.web.mapper.CellTraceMapper;
import com.navercorp.pinpoint.web.mapper.SpanMapperV2;
import com.navercorp.pinpoint.web.mapper.TargetSpanDecoder;
import com.navercorp.pinpoint.web.vo.GetTraceInfo;
import com.navercorp.pinpoint.web.vo.SpanHint;

import com.google.common.collect.Lists;
import org.apache.commons.collections.CollectionUtils;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.client.Get;
import org.apache.hadoop.hbase.filter.BinaryPrefixComparator;
import org.apache.hadoop.hbase.filter.CompareFilter;
import org.apache.hadoop.hbase.filter.Filter;
import org.apache.hadoop.hbase.filter.QualifierFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Woonduk Kang(emeroad)
 */
@Repository
public class HbaseTraceDaoV2 extends AbstractHbaseDao implements TraceDao {

    private final Logger logger = LoggerFactory.getLogger(this.getClass());

    @Autowired
    private HbaseOperations2 template2;

    @Autowired
    @Qualifier("traceRowKeyEncoderV2")
    private RowKeyEncoder<TransactionId> rowKeyEncoder;

    @Autowired
    @Qualifier("traceRowKeyDecoderV2")
    private RowKeyDecoder<TransactionId> rowKeyDecoder;

    private RowMapper<List<SpanBo>> spanMapperV2;

    @Value("#{pinpointWebProps['web.hbase.selectSpans.limit'] ?: 500}")
    private int selectSpansLimit;

    @Value("#{pinpointWebProps['web.hbase.selectAllSpans.limit'] ?: 500}")
    private int selectAllSpansLimit;

    private final Filter spanFilter = createSpanQualifierFilter();

    @PostConstruct
    private void setup() {
        SpanMapperV2 spanMapperV2 = new SpanMapperV2(rowKeyDecoder);
        final Logger logger = LoggerFactory.getLogger(spanMapperV2.getClass());
        if (logger.isDebugEnabled()) {
            this.spanMapperV2 = CellTraceMapper.wrap(spanMapperV2);
        } else {
            this.spanMapperV2 = spanMapperV2;
        }
    }

    @Override
    public List<SpanBo> selectSpan(TransactionId transactionId) {
        if (transactionId == null) {
            throw new NullPointerException("transactionId must not be null");
        }

        byte[] transactionIdRowKey = rowKeyEncoder.encodeRowKey(transactionId);
        TableName traceTableName = getTableName();
        return template2.get(traceTableName, transactionIdRowKey, getColumnFamilyName(), spanMapperV2);
    }


    @Override
    public List<List<SpanBo>> selectSpans(List<GetTraceInfo> getTraceInfoList) {
        return selectSpans(getTraceInfoList, selectSpansLimit);
    }

    List<List<SpanBo>> selectSpans(List<GetTraceInfo> getTraceInfoList, int eachPartitionSize) {
        if (CollectionUtils.isEmpty(getTraceInfoList)) {
            return Collections.emptyList();
        }

        List<List<GetTraceInfo>> partitionGetTraceInfoList = partition(getTraceInfoList, eachPartitionSize);
        return partitionSelect(partitionGetTraceInfoList, getColumnFamilyName(), spanFilter);
    }

    @Override
    public List<List<SpanBo>> selectAllSpans(List<TransactionId> transactionIdList) {
        return selectAllSpans(transactionIdList, selectAllSpansLimit);
    }

    List<List<SpanBo>> selectAllSpans(List<TransactionId> transactionIdList, int eachPartitionSize) {
        if (CollectionUtils.isEmpty(transactionIdList)) {
            return Collections.emptyList();
        }

        List<GetTraceInfo> getTraceInfoList = new ArrayList<>(transactionIdList.size());
        for (TransactionId transactionId : transactionIdList) {
            getTraceInfoList.add(new GetTraceInfo(transactionId));
        }

        List<List<GetTraceInfo>> partitionGetTraceInfoList = partition(getTraceInfoList, eachPartitionSize);
        return partitionSelect(partitionGetTraceInfoList, getColumnFamilyName(), null);
    }

    private List<List<GetTraceInfo>> partition(List<GetTraceInfo> getTraceInfoList, int maxTransactionIdListSize) {
        return Lists.partition(getTraceInfoList, maxTransactionIdListSize);
    }

    private List<List<SpanBo>> partitionSelect(List<List<GetTraceInfo>> partitionGetTraceInfoList, byte[] columnFamily, Filter filter) {
        if (CollectionUtils.isEmpty(partitionGetTraceInfoList)) {
            return Collections.emptyList();
        }
        if (columnFamily == null) {
            throw new NullPointerException("columnFamily must not be null.");
        }

        List<List<SpanBo>> spanBoList = new ArrayList<>();
        for (List<GetTraceInfo> getTraceInfoList : partitionGetTraceInfoList) {
            List<List<SpanBo>> result = bulkSelect(getTraceInfoList, columnFamily, filter);
            spanBoList.addAll(result);
        }
        return spanBoList;
    }

    private List<List<SpanBo>> bulkSelect(List<GetTraceInfo> getTraceInfoList, byte[] columnFamily, Filter filter) {
        if (CollectionUtils.isEmpty(getTraceInfoList)) {
            return Collections.emptyList();
        }
        Assert.requireNonNull(columnFamily, "columnFamily must not be null");

        List<Get> getList = createGetList(getTraceInfoList, columnFamily, filter);

        RowMapper<List<SpanBo>> spanMapperAdaptor = newRowMapper(getTraceInfoList);
        return bulkSelect0(getList, spanMapperAdaptor);
    }

    private RowMapper<List<SpanBo>> newRowMapper(List<GetTraceInfo> getTraceInfoList) {
        RequestAwareRowMapper<List<SpanBo>, GetTraceInfo> getTraceInfoRowMapper = new RequestAwareDynamicRowMapper<>(this::getSpanMapper);
        return new RequestAwareRowMapperAdaptor<List<SpanBo>, GetTraceInfo>(getTraceInfoList, getTraceInfoRowMapper);
    }


    private RowMapper<List<SpanBo>> getSpanMapper(GetTraceInfo getTraceInfo) {
        final SpanHint hint = getTraceInfo.getHint();
        if (hint.isSet()) {
            final SpanDecoder targetSpanDecoder = new TargetSpanDecoder(new SpanDecoderV0(), getTraceInfo);
            final RowMapper<List<SpanBo>> spanMapper = new SpanMapperV2(rowKeyDecoder, targetSpanDecoder);
            return spanMapper;
        } else {
            return spanMapperV2;
        }
    }


    private List<Get> createGetList(List<GetTraceInfo> getTraceInfoList, byte[] columnFamily, Filter filter) {
        if (CollectionUtils.isEmpty(getTraceInfoList)) {
            return Collections.emptyList();
        }
        final List<Get> getList = new ArrayList<>(getTraceInfoList.size());
        for (GetTraceInfo getTraceInfo : getTraceInfoList) {
            final Get get = createGet(getTraceInfo.getTransactionId(), columnFamily, filter);
            getList.add(get);
        }
        return getList;
    }

    private List<List<SpanBo>> bulkSelect0(List<Get> multiGet, RowMapper<List<SpanBo>> rowMapperList) {
        if (CollectionUtils.isEmpty(multiGet)) {
            return Collections.emptyList();
        }

        TableName traceTableName = getTableName();
        return template2.get(traceTableName, multiGet, rowMapperList);
    }

    private Get createGet(TransactionId transactionId, byte[] columnFamily, Filter filter) {
        byte[] transactionIdRowKey = rowKeyEncoder.encodeRowKey(transactionId);
        final Get get = new Get(transactionIdRowKey);

        get.addFamily(columnFamily);
        if (filter != null) {
            get.setFilter(filter);
        }
        return get;
    }

    public QualifierFilter createSpanQualifierFilter() {
        byte indexPrefix = SpanEncoder.TYPE_SPAN;
        BinaryPrefixComparator prefixComparator = new BinaryPrefixComparator(new byte[]{indexPrefix});
        QualifierFilter qualifierPrefixFilter = new QualifierFilter(CompareFilter.CompareOp.EQUAL, prefixComparator);
        return qualifierPrefixFilter;
    }

    @Override
    public HbaseColumnFamily getColumnFamily() {
        return HbaseColumnFamily.TRACE_V2_SPAN;
    }

}
