package cn.luwneshacha.platform.engine;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("platform.engine")
public record EngineProperties(URI baseUrl,@DefaultValue("PT10M") Duration timeout) {
  public EngineProperties {
    if(baseUrl==null||baseUrl.getHost()==null||!("http".equals(baseUrl.getScheme())||"https".equals(baseUrl.getScheme()))
        ||baseUrl.getUserInfo()!=null||baseUrl.getQuery()!=null||baseUrl.getFragment()!=null)
      throw new IllegalArgumentException("platform.engine.base-url 必须为审核引擎HTTP地址");
    if(timeout==null||timeout.isNegative()||timeout.isZero()) throw new IllegalArgumentException("审核引擎超时必须大于零");
  }
}
