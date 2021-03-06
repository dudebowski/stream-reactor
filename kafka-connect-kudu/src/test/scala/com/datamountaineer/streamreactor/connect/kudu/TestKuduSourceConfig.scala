package com.datamountaineer.streamreactor.connect.kudu


/**
  * Created by andrew@datamountaineer.com on 24/02/16. 
  * stream-reactor
  */
class TestKuduSourceConfig extends TestBase {
  test("A KuduSinkConfig should return Kudu Master") {
    val config  = new KuduSinkConfig(getConfig)
    config.getString(KuduSinkConfig.KUDU_MASTER) shouldBe KUDU_MASTER
  }
}