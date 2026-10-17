package com.anker.esiotkit.sample.main.mega.thing.mode

data class ThingType(
  /**
   * action , property, event
   */
  val thingModel:String,
  /**
   * the value match action,property,event
   */
  val thingList:List<Thing>
)

data class Thing(
  val thingIdentifier:String,
)
