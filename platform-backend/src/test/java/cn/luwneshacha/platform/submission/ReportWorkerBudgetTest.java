package cn.luwneshacha.platform.submission;

import static org.junit.jupiter.api.Assertions.*;

import cn.luwneshacha.platform.engine.EngineProperties;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ReportWorkerBudgetTest {
  @Test void defaultWorkerCanStartWithReportDeliveryEnabled() {
    var binder=new Binder(new MapConfigurationPropertySource());
    var worker=binder.bindOrCreate("platform.worker",Bindable.of(WorkerProperties.class));
    var engine=new EngineProperties(URI.create("http://engine:18098"),Duration.ofMinutes(10));
    assertDoesNotThrow(()->SubmissionWorker.validateLease(engine,worker));
  }

  @Test void leaseMustIncludeOriginalDownloadAndAllFourOutputs() {
    var engine=new EngineProperties(URI.create("http://engine:18098"),Duration.ofMinutes(10));
    assertThrows(IllegalArgumentException.class,()->SubmissionWorker.validateLease(
        engine,new WorkerProperties(true,Duration.ofMinutes(36))));
    assertDoesNotThrow(()->SubmissionWorker.validateLease(
        engine,new WorkerProperties(true,Duration.ofMinutes(37))));
  }
}
