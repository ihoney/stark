package com.example

import org.junit.{Assert, Test}
import stark.activerecord.services.DSL._
import stark.activerecord.{ModelA, BaseActiveRecordTestCase}

/**
  *
  * @author <a href="mailto:jcai@ganshane.com">Jun Tsai</a>
  * @since 2016-03-14
  */
class DSLTest extends BaseActiveRecordTestCase{
  @Test
  def test_select: Unit = {
    val modelA = new ModelA
    modelA.name = "cctv"
    modelA.save
    val modelA2 = new ModelA
    modelA2.name = "asdf"
    modelA2.save;

    val q1 = select[ModelA](ModelA.name,ModelA.seq) orderBy  ModelA.name
    Assert.assertEquals(2, q1.size)
    val head = q1.head
    val first = q1.head
  }
  @Test
  def test_dsl: Unit ={
    val modelA = new ModelA
    modelA.name = "cctv"
    modelA.save
    val modelA2 = new ModelA
    modelA2.name="asdf"
    modelA2.save;
    {
      val q1 = select[ModelA] orderBy  ModelA.name
      Assert.assertEquals(2, q1.size)
      val q2 = select[ModelA] limit 10 offset 1
      Assert.assertEquals(1, q2.size)

      val q3 = select[ModelA] where ModelA.name === "cctv" and (
        ModelA.seq === 1 or ModelA.name === "cctv" or ModelA.name[String].isNull
          or ModelA.seq[Int] > 1 or ModelA.name === "asdf"
        ) limit 3 offset 0 orderBy ModelA.name[String].desc


      Assert.assertEquals(1, q3.size)

      val q4 = select[ModelA] where ModelA.name === "cctv" or ModelA.seq === 1
      Assert.assertEquals(1, q4.size)
    }


    //update
    {
      Assert.assertEquals(0, select[ModelA] where ModelA.name === "fdsa" size)
      val num = update[ModelA] set (name="fdsa") where ModelA.name === "cctv" or ModelA.seq === 1  execute;
      Assert.assertEquals(1,num)
      Assert.assertEquals(1, select[ModelA] where ModelA.name === "fdsa" size)
    }
    //delete
    {
      Assert.assertEquals(2, select[ModelA].size)
      delete[ModelA] where ModelA.name === "fdsa" execute;
      Assert.assertEquals(1, select[ModelA].size)
    }

  }
}
