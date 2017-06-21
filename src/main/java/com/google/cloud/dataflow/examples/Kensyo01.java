/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.cloud.dataflow.examples;

import java.io.Serializable;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import com.google.cloud.dataflow.sdk.Pipeline;
import com.google.cloud.dataflow.sdk.io.BigQueryIO;
import com.google.cloud.dataflow.sdk.io.PubsubIO;
import com.google.cloud.dataflow.sdk.options.DataflowPipelineOptions;
import com.google.cloud.dataflow.sdk.options.Default;
import com.google.cloud.dataflow.sdk.options.DefaultValueFactory;
import com.google.cloud.dataflow.sdk.options.Description;
import com.google.cloud.dataflow.sdk.options.PipelineOptions;
import com.google.cloud.dataflow.sdk.options.PipelineOptionsFactory;
import com.google.cloud.dataflow.sdk.transforms.Aggregator;
import com.google.cloud.dataflow.sdk.transforms.Count;
import com.google.cloud.dataflow.sdk.transforms.Create;
import com.google.cloud.dataflow.sdk.transforms.DoFn;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.PTransform;
import com.google.cloud.dataflow.sdk.transforms.ParDo;
import com.google.cloud.dataflow.sdk.transforms.Sum;
import com.google.cloud.dataflow.sdk.transforms.windowing.FixedWindows;
import com.google.cloud.dataflow.sdk.transforms.windowing.Window;
import com.google.cloud.dataflow.sdk.util.gcsfs.GcsPath;
import com.google.cloud.dataflow.sdk.values.KV;
import com.google.cloud.dataflow.sdk.values.PCollection;


/**
 * An example that counts words in Shakespeare and includes Dataflow best practices.
 *
 * <p>This class, {@link Kensyo01}, is the second in a series of four successively more detailed
 * 'word count' examples. You may first want to take a look at {@link MinimalWordCount}.
 * After you've looked at this example, then see the {@link DebuggingWordCount}
 * pipeline, for introduction of additional concepts.
 *
 * <p>For a detailed walkthrough of this example, see
 *   <a href="https://cloud.google.com/dataflow/java-sdk/wordcount-example">
 *   https://cloud.google.com/dataflow/java-sdk/wordcount-example
 *   </a>
 *
 * <p>Basic concepts, also in the MinimalWordCount example:
 * Reading text files; counting a PCollection; writing to GCS.
 *
 * <p>New Concepts:
 * <pre>
 *   1. Executing a Pipeline both locally and using the Dataflow service
 *   2. Using ParDo with static DoFns defined out-of-line
 *   3. Building a composite transform
 *   4. Defining your own pipeline options
 * </pre>
 *
 * <p>Concept #1: you can execute this pipeline either locally or using the Dataflow service.
 * These are now command-line options and not hard-coded as they were in the MinimalWordCount
 * example.
 * To execute this pipeline locally, specify general pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 * }
 * </pre>
 * and a local output file or output prefix on GCS:
 * <pre>{@code
 *   --output=[YOUR_LOCAL_FILE | gs://YOUR_OUTPUT_PREFIX]
 * }</pre>
 *
 * <p>To execute this pipeline using the Dataflow service, specify pipeline configuration:
 * <pre>{@code
 *   --project=YOUR_PROJECT_ID
 *   --stagingLocation=gs://YOUR_STAGING_DIRECTORY
 *   --runner=BlockingDataflowPipelineRunner
 * }
 * </pre>
 * and an output prefix on GCS:
 * <pre>{@code
 *   --output=gs://YOUR_OUTPUT_PREFIX
 * }</pre>
 *
 * <p>The input file defaults to {@code gs://dataflow-samples/shakespeare/kinglear.txt} and can be
 * overridden with {@code --inputFile}.
 */
public class Kensyo01 {

	static  Logger LOG = LoggerFactory.getLogger( Kensyo01.class );
  /**
   * Concept #2: You can make your pipeline code less verbose by defining your DoFns statically out-
   * of-line. This DoFn tokenizes lines of text into individual words; we pass it to a ParDo in the
   * pipeline.
   */
  static class ExtractWordsFn extends DoFn<String, String> {
    private final Aggregator<Long, Long> emptyLines =
        createAggregator("emptyLines", new Sum.SumLongFn());

    @Override
    public void processElement(ProcessContext c) {
      if (c.element().trim().isEmpty()) {
        emptyLines.addValue(1L);
      }

      // Split the line into words.
      String[] words = c.element().split("[^a-zA-Z']+");

      // Output each word encountered into the output PCollection.
      for (String word : words) {
        if (!word.isEmpty()) {
          c.output(word);
        }
      }
    }
  }

  /** A DoFn that converts a Word and Count into a printable string. */
  public static class FormatAsTextFn extends DoFn<KV<String, Long>, String> {
    @Override
    public void processElement(ProcessContext c) {
      c.output(c.element().getKey() + ": " + c.element().getValue());
    }
  }


  public static class CountWords extends PTransform<PCollection<String>,
      PCollection<KV<String, Long>>> {
    @Override
    public PCollection<KV<String, Long>> apply(PCollection<String> lines) {

      // Convert lines of text into individual words.
      PCollection<String> words = lines.apply(
          ParDo.of(new ExtractWordsFn()));

      // Count the number of times each word occurs.
      PCollection<KV<String, Long>> wordCounts =
          words.apply(Count.<String>perElement());

      return wordCounts;
    }
  }


  /**
   * Options supported by {@link Kensyo01}.
   *
   * <p>Concept #4: Defining your own configuration options. Here, you can add your own arguments
   * to be processed by the command-line parser, and specify default values for them. You can then
   * access the options values in your pipeline code.
   *
   * <p>Inherits standard configuration options.
   */
  public interface WordCountOptions extends PipelineOptions {
    @Description("Path of the file to read from")
    @Default.String("gs://dataflow-samples/shakespeare/kinglear.txt")
    String getInputFile();
    void setInputFile(String value);

    @Description("Path of the file to write to")
    @Default.InstanceFactory(OutputFactory.class)
    String getOutput();
    void setOutput(String value);

    /**
     * Returns "gs://${YOUR_STAGING_DIRECTORY}/counts.txt" as the default destination.
     */
    class OutputFactory implements DefaultValueFactory<String> {
      @Override
      public String create(PipelineOptions options) {
        DataflowPipelineOptions dataflowOptions = options.as(DataflowPipelineOptions.class);
        if (dataflowOptions.getStagingLocation() != null) {
          return GcsPath.fromUri(dataflowOptions.getStagingLocation())
              .resolve("counts.txt").toString();
        } else {
          throw new IllegalArgumentException("Must specify --output or --stagingLocation");
        }
      }
    }

  }


  public static class LogToStringFn extends DoFn<ActionLog, String> {
    @Override
    public void processElement(ProcessContext c) {
      c.output(c.element().toString());
    }
  }

  public static class LogIncreaseFn extends DoFn<String, String> {
	    @Override
	    public void processElement(ProcessContext c) {
	      ActionLog newData = new ActionLog();
	      LOG.info("新規データ：" + newData.toString());
	      c.output(newData.toString());
	    }
	  }

  public static class StringToLogKVFn extends DoFn<String, KV<Integer,ActionLog>> {

	    public Boolean withTimestamp;

	  	public StringToLogKVFn(Boolean withTimestamp) {
	  		this.withTimestamp = withTimestamp;
		}

	    @Override
	    public void processElement(ProcessContext c) {
	    	LOG.info("メッセージ原文:" + c.element());
	    	ActionLog newData = new ActionLog(c.element());
	    	if(withTimestamp) {
		    	c.outputWithTimestamp(KV.of(newData.id, newData), new Instant(newData.realChangeTime.getTime()));
	    	} else {
	    		c.output(KV.of(newData.id, newData));
	    	}
	    }
	  }

  public static class LogGroupToTableRowFn extends DoFn<KV<Integer, Iterable<ActionLog>>, TableRow> {

	    public static TableSchema schema;

	  	static {
	  	  List<TableFieldSchema> fields = new ArrayList<>();
	  	  fields.add(new TableFieldSchema().setName("id").setType("INTEGER"));
	  	  fields.add(new TableFieldSchema().setName("data").setType("STRING"));
	  	  schema = new TableSchema().setFields(fields);
	  	}

	    @Override
	    public void processElement(ProcessContext c) {
	    	TableRow row = new TableRow();
	    	row.set("id", c.element().getKey());

	    	String data = "";
	    	for(ActionLog log : c.element().getValue()) {
	    		data += log.toString() + ";";
	    	}

	    	row.set("data", data);

	    	c.output(row);
	    }
  }

  public static class StringToTableRowFn extends DoFn<String, TableRow> {

	    public static TableSchema schema;

	  	static {
	  	  List<TableFieldSchema> fields = new ArrayList<>();
	  	  fields.add(new TableFieldSchema().setName("id").setType("INTEGER"));
	  	  fields.add(new TableFieldSchema().setName("data").setType("STRING"));
	  	  schema = new TableSchema().setFields(fields);
	  	}

	    @Override
	    public void processElement(ProcessContext c) {
	    	TableRow row = new TableRow();
	    	ActionLog log = new ActionLog(c.element());
	    	row.set("id", log.id);
	    	row.set("data", log.toString());
	    	c.output(row);
	    }
}


  public static class ActionLog implements Serializable {
	  static public final String DATE_PATTERN ="yyyy-MM-dd'T'HH:mm:ss";

	  public Integer id;			// ユーザID
	  public Integer channel;		// 変更後のチャンネル番号1-12
	  public Date realChangeTime;	// 実際にチャンネルを変えた時刻

	  public ActionLog() {
		  id = (int)(Math.random()*100);	//ユーザ数100人
		  channel = (int)(Math.random()*12 + 1);
		  realChangeTime = new Date(System.currentTimeMillis() - 1000 * (int)(Math.random()*600));	// 最大10分(600秒)遅れ
	  }

	  public ActionLog(String data) {
		  String [] datas = data.split(",");

		  this.id = Integer.valueOf(datas[0]);
		  this.channel = Integer.valueOf(datas[1]);
		  try {
			  this.realChangeTime = (new SimpleDateFormat(DATE_PATTERN)).parse(datas[2]);
		} catch (ParseException e) {
    		LOG.info("日付エラー？：" + datas[2]);
			throw new RuntimeException(e);
		}
	  }

	  @Override
	  public String toString() {
		  return this.id + "," + this.channel + "," + (new SimpleDateFormat(DATE_PATTERN)).format(this.realChangeTime);
	  }
  }

  public static void main(String[] args) {

	  List<String> args2 = Arrays.asList(
			"--runner=DataflowPipelineRunner",
			"--project=sylvan-overview-145200",
			"--stagingLocation=gs://dataflow-01/stage",
			"--tempLocation=gs://dataflow-01/temp",
			"--maxNumWorkers=1",
			"--diskSizeGb=1",
			"--workerMachineType=n1-standard-1",
			"--streaming=true"
			);

    WordCountOptions options = PipelineOptionsFactory.fromArgs((String[])args2.toArray()).withValidation()
      .as(WordCountOptions.class);
    Pipeline p = Pipeline.create(options);

    List<ActionLog> datas = Arrays.asList(
    		new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(),
    		new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(),
    		new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog(), new ActionLog()
    		);

    // シードデータ(固定数)の投入(こちらは実質的に固定のバッチ処理)
    p.apply(Create.of(datas))
     .apply(ParDo.of(new LogToStringFn()))
     .apply(PubsubIO.Write.named("WriteSeed").topic("projects/sylvan-overview-145200/topics/kensyo-01"));

    // シードデータを元に新規データを作成してTopicに投げなおすパイプライン(大体毎秒シードデータ数が作成され続ける速度でデータが作成され続ける動きになる)
    p.apply(PubsubIO.Read.named("PubSubRead").subscription("projects/sylvan-overview-145200/subscriptions/kensyo-01"))
     .apply(ParDo.of(new LogIncreaseFn()))
     .apply(PubsubIO.Write.named("WriteData").topic("projects/sylvan-overview-145200/topics/kensyo-01"));

    PCollection<String> pubsubPCollection = p.apply(PubsubIO.Read.named("PubSubRead").subscription("projects/sylvan-overview-145200/subscriptions/kensyo-02"));

    // Group by 結果をBigQueryへ出力
    pubsubPCollection
     .apply(ParDo.of(new StringToLogKVFn(false)))
//     .apply(Window.triggering(AfterFirst.of(AfterPane.elementCountAtLeast(2))))
     .apply(Window.into(FixedWindows.of(Duration.standardSeconds(30))))
     .apply(GroupByKey.create())
     .apply(ParDo.of(new LogGroupToTableRowFn()))
     .apply(BigQueryIO.Write.named("BigQueryWriteGroupby")
    		 .to("sylvan-overview-145200:test01.dataflow_groupby")
    		 .withSchema(LogGroupToTableRowFn.schema)
    		 .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
    		 .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
    		 );

    // 受信ログ全てを生でBigQueryへ出力
    pubsubPCollection
     .apply(ParDo.of(new StringToTableRowFn()))
     .apply(BigQueryIO.Write.named("BigQueryWriteRaw")
    		 .to("sylvan-overview-145200:test01.dataflow_raw")
    		 .withSchema(LogGroupToTableRowFn.schema)
    		 .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_IF_NEEDED)
    		 .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
    		 );

    p.run();
  }
}
