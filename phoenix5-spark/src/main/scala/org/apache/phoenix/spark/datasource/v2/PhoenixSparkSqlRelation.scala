/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.spark.datasource.v2

import org.apache.phoenix.spark.datasource.v2.reader.PhoenixDataSourceReader
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, Row, SQLContext, SaveMode, SparkSession}
import org.apache.spark.sql.catalyst.expressions.GenericRowWithSchema
import org.apache.spark.sql.execution.datasources.v2.DataSourceRDD
import org.apache.spark.sql.sources.v2.DataSourceOptions
import org.apache.spark.sql.sources.{BaseRelation, Filter, InsertableRelation, PrunedFilteredScan}
import org.apache.spark.sql.types.StructType

import scala.collection.JavaConverters._

case class PhoenixSparkSqlRelation(
                                    @transient sparkSession: SparkSession,
                                    params: Map[String, String]
                                  ) extends BaseRelation with PrunedFilteredScan with InsertableRelation {

  override def schema: StructType = dataSourceReader.readSchema()

  override def sqlContext: SQLContext = sparkSession.sqlContext

  private def dataSourceReader: PhoenixDataSourceReader = new PhoenixDataSourceReader(dataSourceOptions)

  private def dataSourceOptions = new DataSourceOptions(params.asJava)

  override def buildScan(requiredColumns: Array[String], filters: Array[Filter]): RDD[Row] = {
    val requiredSchema =    StructType(requiredColumns.flatMap(c => schema.fields.find(_.name == c)))

    val reader: PhoenixDataSourceReader = dataSourceReader
    reader.pushFilters(filters)
    reader.pruneColumns(requiredSchema)
    val rdd = new DataSourceRDD(
      sqlContext.sparkContext,
      reader.planInputPartitions().asScala
    )
    rdd.map(ir => {
      new GenericRowWithSchema(ir.toSeq(requiredSchema).toArray, requiredSchema)
    })
  }


  override def insert(data: DataFrame, overwrite: Boolean): Unit = {
    data
      .write
      .format("phoenix")
      .option(PhoenixDataSource.TABLE, params(PhoenixDataSource.TABLE))
      .option(PhoenixDataSource.JDBC_URL, PhoenixDataSource.getJdbcUrlFromOptions(dataSourceOptions))
      .option(PhoenixDataSource.SKIP_NORMALIZING_IDENTIFIER,
        params.getOrElse(PhoenixDataSource.SKIP_NORMALIZING_IDENTIFIER, "false"))
      .option(PhoenixDataSource.TENANT_ID,params.getOrElse(PhoenixDataSource.TENANT_ID,null))
      .mode(SaveMode.Overwrite)
      .save()
  }


}

