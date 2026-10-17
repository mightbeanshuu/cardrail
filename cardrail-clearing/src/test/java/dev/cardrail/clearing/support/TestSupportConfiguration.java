package dev.cardrail.clearing.support;

import org.springframework.batch.core.Job;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class TestSupportConfiguration {

    @Bean
    static SettlementStoreFaults settlementStoreFaults() {
        return new SettlementStoreFaults();
    }

    @Bean
    JobLauncherTestUtils jobLauncherTestUtils(Job settlementJob, JobLauncher jobLauncher, JobRepository jobRepository) {
        JobLauncherTestUtils utils = new JobLauncherTestUtils();
        utils.setJob(settlementJob);
        utils.setJobLauncher(jobLauncher);
        utils.setJobRepository(jobRepository);
        return utils;
    }

    @Bean
    JobRepositoryTestUtils jobRepositoryTestUtils(JobRepository jobRepository) {
        return new JobRepositoryTestUtils(jobRepository);
    }

    @Bean
    TestFixtures testFixtures() {
        return new TestFixtures();
    }
}
