package com.anker.esiotkit.sample

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import com.anker.esiotkit.support.log.appLogger
import kotlinx.coroutines.flow.MutableStateFlow
import java.lang.ref.WeakReference
import java.util.*

/**
 * @version 2022/5/21
 */
object ESIotActivityManager {
  private var mCurrentActivity: WeakReference<Activity>? = null
  private val mActivityStack = Stack<Activity>()
  private var resumeActivityName: String? = ""
  var isBackground = MutableStateFlow(false)
  private var resumedActivity = 0

  private val activityLifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
      mActivityStack.push(activity)
    }

    override fun onActivityStarted(activity: Activity) {
    }

    override fun onActivityResumed(activity: Activity) {
      resumedActivity++
      isBackground.value = false
      if (resumeActivityName != activity.javaClass.simpleName) {
        resumeActivityName = activity.javaClass.simpleName
      }
    }

    override fun onActivityPaused(activity: Activity) {
    }

    override fun onActivityStopped(activity: Activity) {
      resumedActivity--
      isBackground.value = resumedActivity == 0
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

    }

    override fun onActivityDestroyed(activity: Activity) {
      mActivityStack.remove(activity)
    }
  }

  fun init(application: Application) {
    application.registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
    registerCommandSendByWebrtcFallback()
    //接入firebase，这里不用捕获了
//    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
//      appLogger.e("Application/uncaughtExceptionHandler/thread:${thread.name}")
//      appLogger.e("Application/uncaughtExceptionHandler/throwable:${throwable.stackTraceToString()}")
//      throwable.printStackTrace()
//    }
  }

  private fun registerCommandSendByWebrtcFallback() {
    try {
      val p2pClientClass = Class.forName("com.eufy.security.p2p.P2PClient")
      val getInstanceMethod = p2pClientClass.getMethod("getInstance")
      val p2pClientInstance = getInstanceMethod.invoke(null)

      val commandSendByWebrtcClass = Class.forName("com.eufy.security.p2p.support.function.CommandSendByWebrtc")

      val proxyInstance = java.lang.reflect.Proxy.newProxyInstance(
        commandSendByWebrtcClass.classLoader,
        arrayOf(commandSendByWebrtcClass)
      ) { _, method, _ ->
        if (method.name == "isWebrtc") {
          false
        } else {
          null
        }
      }

      val setCommandSendByWebrtcMethod = p2pClientClass.getMethod("setCommandSendByWebrtc", commandSendByWebrtcClass)
      setCommandSendByWebrtcMethod.invoke(p2pClientInstance, proxyInstance)
      appLogger.i("Successfully registered CommandSendByWebrtc fallback dynamically via reflection.")
    } catch (e: Throwable) {
      appLogger.w("Dynamic CommandSendByWebrtc fallback registration skipped or failed: ${e.message}")
    }
  }

  fun finishAllActivity() {
    while(mActivityStack.size > 0) {
      mActivityStack.pop().finish()
    }
  }

  /**如果Activity栈中存在这个Activity，则从栈顶一直关闭Activity直到将这个Activity关闭*/
  fun finishIfContainsActivity(className: String) {
    while(mActivityStack.any { it.javaClass.simpleName == className }) {
      mActivityStack.pop().also {
        it.finish()
      }
    }
  }

  fun hasActivityInStack(clazz: Class<*>): Boolean {
    mActivityStack.forEach {
      if (it.javaClass.simpleName == clazz.simpleName) {
        return true
      }
    }
    return false
  }

  fun getFirstActivity(): Activity? {
    return mActivityStack.firstOrNull()
  }

  fun finishCurrentActivity() {
    if (mCurrentActivity != null) {
      val activity = mCurrentActivity!!.get()
      activity?.finish()
    }
  }

  fun startActivityAndFinishOthers(clazz: Class<*>) {
    while(mActivityStack.size > 0) {
      val activity = mActivityStack.pop()
      if (mActivityStack.size == 0) {
        activity.startActivity(Intent(activity, clazz))
      }
      activity.finish()
    }
  }

  fun startActivityAndFinishOthers(intent: Intent) {
    while(mActivityStack.size > 0) {
      val activity = mActivityStack.pop()
      if (mActivityStack.size == 0) {
        activity.startActivity(intent)
      }
      activity.finish()
    }
  }

}