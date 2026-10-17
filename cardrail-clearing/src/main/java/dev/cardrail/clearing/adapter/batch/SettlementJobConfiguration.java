package dev.cardrail.clearing.adapter.batch;

import dev.cardrail.clearing.application.SettlementProperties;
import dev.cardrail.clearing.domain.ClearingStatus;
import dev.cardrail.clearing.domain.settlement.SettlementItem;
import dev.cardrail.clearing.port.SettlementStore;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.builder.JdbcPagingItemReaderBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;

/**
 * End-of-day settlement for one business date:
 * <ol>
 *   <li>{@value #ACCUMULATE}: page through pending records, claim each chunk for the run and add its
 *       per-party partial sums, all in the chunk's transaction;</li>
 *   <li>{@value #PUBLISH}: fold the partials into positions, reconcile, store them and mark the
 *       claimed records SETTLED, in one transaction;</li>
 *   <li>{@value #REPORT}: write the CSV report.</li>
 * </ol>
 * A date's run settles every pending record with that business date or an earlier one, so a record
 * that arrives after its own date was settled (Kafka lag, an outage) is picked up by the next run.
 */
@Configuration(proxyBeanMethods = false)
class SettlementJobConfiguration {

    static final String JOB = "settlementJob";
    static final String BUSINESS_DATE = "businessDate";
    static final String ACCUMULATE = "accumulatePositions";
    static final String PUBLISH = "publishPositions";
    static final String REPORT = "writeReport";

    @Bean
    Job settlementJob(JobRepository jobRepository, Step accumulatePositions, Step publishPositions, Step writeReport) {
        return new JobBuilder(JOB, jobRepository)
                .start(accumulatePositions)
                .next(publishPositions)
                .next(writeReport)
                .build();
    }

    @Bean
    Step accumulatePositions(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                             JdbcPagingItemReader<SettlementItem> pendingRecordReader,
                             ItemWriter<SettlementItem> positionWriter, SettlementProperties properties) {
        return new StepBuilder(ACCUMULATE, jobRepository)
                .<SettlementItem, SettlementItem>chunk(properties.chunkSize(), transactionManager)
                .reader(pendingRecordReader)
                .writer(positionWriter)
                .build();
    }

    /**
     * Keyset paging on id, not OFFSET: the reader's restart state is the last id committed, and rows
     * claimed by earlier chunks drop out of the WHERE clause without shifting later pages.
     * Spring Batch picks the vendor's paging SQL from the DataSource (LIMIT on PostgreSQL, ROWNUM on Oracle).
     */
    @Bean
    @StepScope
    JdbcPagingItemReader<SettlementItem> pendingRecordReader(
            DataSource dataSource, SettlementProperties properties,
            @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate) {
        return new JdbcPagingItemReaderBuilder<SettlementItem>()
                .name("pendingRecordReader")
                .dataSource(dataSource)
                .selectClause("SELECT id, acquirer_id, issuer_bin, currency, amount_minor, interchange_fee_minor")
                .fromClause("FROM clearing_record")
                .whereClause("WHERE status = :pending AND business_date <= :businessDate AND settlement_date IS NULL")
                .parameterValues(Map.of("pending", ClearingStatus.PENDING_SETTLEMENT.name(), "businessDate", businessDate))
                .sortKeys(Map.of("id", Order.ASCENDING))
                .pageSize(properties.chunkSize())
                .rowMapper((rs, n) -> new SettlementItem(rs.getLong("id"), rs.getString("acquirer_id"),
                        rs.getString("issuer_bin"), rs.getInt("currency"), rs.getLong("amount_minor"),
                        rs.getLong("interchange_fee_minor")))
                .build();
    }

    @Bean
    @StepScope
    PositionAccumulatingWriter positionWriter(SettlementStore store, SettlementProperties properties,
                                              @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate) {
        return new PositionAccumulatingWriter(store, properties.networkId(), businessDate);
    }

    @Bean
    Step publishPositions(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                          PublishPositionsTasklet publishPositionsTasklet) {
        return new StepBuilder(PUBLISH, jobRepository)
                .tasklet(publishPositionsTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    PublishPositionsTasklet publishPositionsTasklet(SettlementStore store, Clock clock,
                                                    @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate) {
        return new PublishPositionsTasklet(store, clock, businessDate);
    }

    @Bean
    Step writeReport(JobRepository jobRepository, PlatformTransactionManager transactionManager,
                     WriteReportTasklet writeReportTasklet) {
        return new StepBuilder(REPORT, jobRepository)
                .tasklet(writeReportTasklet, transactionManager)
                .build();
    }

    @Bean
    @StepScope
    WriteReportTasklet writeReportTasklet(SettlementStore store, SettlementProperties properties,
                                          @Value("#{jobParameters['" + BUSINESS_DATE + "']}") LocalDate businessDate) {
        return new WriteReportTasklet(store, properties.reportDirectory(), businessDate);
    }
}
