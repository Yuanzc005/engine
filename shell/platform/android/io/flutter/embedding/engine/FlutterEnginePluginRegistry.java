// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.embedding.engine;

import android.app.Activity;
import android.app.Service;
import android.arch.lifecycle.Lifecycle;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.Context;
import android.content.Intent;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.embedding.engine.plugins.PluginRegistry;
import io.flutter.embedding.engine.plugins.activity.ActivityAware;
import io.flutter.embedding.engine.plugins.activity.ActivityControlSurface;
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding;
import io.flutter.embedding.engine.plugins.broadcastreceiver.BroadcastReceiverAware;
import io.flutter.embedding.engine.plugins.broadcastreceiver.BroadcastReceiverControlSurface;
import io.flutter.embedding.engine.plugins.broadcastreceiver.BroadcastReceiverPluginBinding;
import io.flutter.embedding.engine.plugins.contentprovider.ContentProviderAware;
import io.flutter.embedding.engine.plugins.contentprovider.ContentProviderControlSurface;
import io.flutter.embedding.engine.plugins.contentprovider.ContentProviderPluginBinding;
import io.flutter.embedding.engine.plugins.service.ServiceAware;
import io.flutter.embedding.engine.plugins.service.ServiceControlSurface;
import io.flutter.embedding.engine.plugins.service.ServicePluginBinding;

class FlutterEnginePluginRegistry implements PluginRegistry,
    ActivityControlSurface,
    ServiceControlSurface,
    BroadcastReceiverControlSurface,
    ContentProviderControlSurface {
  private static final String TAG = "EnginePluginRegistry";

  // PluginRegistry
  private final Map<Class<? extends FlutterPlugin>, FlutterPlugin> plugins = new HashMap<>();

  // Standard FlutterPlugin
  private final FlutterPlugin.FlutterPluginBinding pluginBinding;
  private final FlutterEngineAndroidLifecycle flutterEngineAndroidLifecycle;

  // ActivityAware
  private final Map<Class<? extends FlutterPlugin>, ActivityAware> activityAwarePlugins = new HashMap<>();
  private Activity activity;
  private FlutterEngineActivityPluginBinding activityPluginBinding;

  // ServiceAware
  private final Map<Class<? extends FlutterPlugin>, ServiceAware> serviceAwarePlugins = new HashMap<>();
  private Service service;
  private FlutterEngineServicePluginBinding servicePluginBinding;

  // BroadcastReceiver
  private final Map<Class<? extends FlutterPlugin>, BroadcastReceiverAware> broadcastReceiverAwarePlugins = new HashMap<>();
  private BroadcastReceiver broadcastReceiver;
  private FlutterEngineBroadcastReceiverPluginBinding broadcastReceiverPluginBinding;

  // ContentProvider
  private final Map<Class<? extends FlutterPlugin>, ContentProviderAware> contentProviderAwarePlugins = new HashMap<>();
  private ContentProvider contentProvider;
  private FlutterEngineContentProviderPluginBinding contentProviderPluginBinding;

  FlutterEnginePluginRegistry(
      @NonNull Context appContext,
      @NonNull FlutterEngine flutterEngine,
      @NonNull FlutterEngineAndroidLifecycle lifecycle
  ) {
    flutterEngineAndroidLifecycle = lifecycle;
    pluginBinding = new FlutterPlugin.FlutterPluginBinding(
        appContext,
        flutterEngine,
        lifecycle
    );
  }

  public void destroy() {
    // Detach from any Android component that we may currently be attached to, e.g., Activity, Service,
    // BroadcastReceiver, ContentProvider. This must happen before removing all plugins so that the
    // plugins have an opportunity to clean up references as a result of component detachment.
    detachFromAndroidComponent();

    // Push FlutterEngine's Lifecycle to the DESTROYED state. This must happen before removing all
    // plugins so that the plugins have an opportunity to clean up references as a result of moving
    // to the DESTROYED state.
    flutterEngineAndroidLifecycle.destroy();

    // Remove all registered plugins.
    removeAll();
  }

  public void add(@NonNull FlutterPlugin plugin) {
    // Add the plugin to our generic set of plugins and notify the plugin
    // that is has been attached to an engine.
    plugins.put(plugin.getClass(), plugin);
    plugin.onAttachedToEngine(pluginBinding);

    // For ActivityAware plugins, add the plugin to our set of ActivityAware
    // plugins, and if this engine is currently attached to an Activity,
    // notify the ActivityAware plugin that it is now attached to an Activity.
    if (plugin instanceof ActivityAware) {
      ActivityAware activityAware = (ActivityAware) plugin;
      activityAwarePlugins.put(plugin.getClass(), activityAware);

      if (isAttachedToActivity()) {
        activityAware.onAttachedToActivity(activityPluginBinding);
      }
    }

    // For ServiceAware plugins, add the plugin to our set of ServiceAware
    // plugins, and if this engine is currently attached to a Service,
    // notify the ServiceAware plugin that it is now attached to a Service.
    if (plugin instanceof ServiceAware) {
      ServiceAware serviceAware = (ServiceAware) plugin;
      serviceAwarePlugins.put(plugin.getClass(), serviceAware);

      if (isAttachedToService()) {
        serviceAware.onAttachedToService(servicePluginBinding);
      }
    }

    // For BroadcastReceiverAware plugins, add the plugin to our set of BroadcastReceiverAware
    // plugins, and if this engine is currently attached to a BroadcastReceiver,
    // notify the BroadcastReceiverAware plugin that it is now attached to a BroadcastReceiver.
    if (plugin instanceof BroadcastReceiverAware) {
      BroadcastReceiverAware broadcastReceiverAware = (BroadcastReceiverAware) plugin;
      broadcastReceiverAwarePlugins.put(plugin.getClass(), broadcastReceiverAware);

      if (isAttachedToBroadcastReceiver()) {
        broadcastReceiverAware.onAttachedToBroadcastReceiver(broadcastReceiverPluginBinding);
      }
    }

    // For ContentProviderAware plugins, add the plugin to our set of ContentProviderAware
    // plugins, and if this engine is currently attached to a ContentProvider,
    // notify the ContentProviderAware plugin that it is now attached to a ContentProvider.
    if (plugin instanceof ContentProviderAware) {
      ContentProviderAware contentProviderAware = (ContentProviderAware) plugin;
      contentProviderAwarePlugins.put(plugin.getClass(), contentProviderAware);

      if (isAttachedToContentProvider()) {
        contentProviderAware.onAttachedToContentProvider(contentProviderPluginBinding);
      }
    }
  }

  public void add(@NonNull Set<FlutterPlugin> plugins) {
    for (FlutterPlugin plugin : plugins) {
      add(plugin);
    }
  }

  public boolean has(@NonNull Class<? extends FlutterPlugin> pluginClass) {
    return plugins.containsKey(pluginClass);
  }

  public FlutterPlugin get(@NonNull Class<? extends FlutterPlugin> pluginClass) {
    return plugins.get(pluginClass);
  }

  public void remove(@NonNull Class<? extends FlutterPlugin> pluginClass) {
    FlutterPlugin plugin = plugins.get(pluginClass);
    if (plugin != null) {
      // For ActivityAware plugins, notify the plugin that it is detached from
      // an Activity if an Activity is currently attached to this engine. Then
      // remove the plugin from our set of ActivityAware plugins.
      if (plugin instanceof ActivityAware) {
        if (isAttachedToActivity()) {
          ActivityAware activityAware = (ActivityAware) plugin;
          activityAware.onDetachedFromActivity();
        }
        activityAwarePlugins.remove(pluginClass);
      }

      // For ServiceAware plugins, notify the plugin that it is detached from
      // a Service if a Service is currently attached to this engine. Then
      // remove the plugin from our set of ServiceAware plugins.
      if (plugin instanceof ServiceAware) {
        if (isAttachedToService()) {
          ServiceAware serviceAware = (ServiceAware) plugin;
          serviceAware.onDetachedFromService();
        }
        serviceAwarePlugins.remove(pluginClass);
      }

      // For BroadcastReceiverAware plugins, notify the plugin that it is detached from
      // a BroadcastReceiver if a BroadcastReceiver is currently attached to this engine. Then
      // remove the plugin from our set of BroadcastReceiverAware plugins.
      if (plugin instanceof BroadcastReceiverAware) {
        if (isAttachedToBroadcastReceiver()) {
          BroadcastReceiverAware broadcastReceiverAware = (BroadcastReceiverAware) plugin;
          broadcastReceiverAware.onDetachedFromBroadcastReceiver();
        }
        broadcastReceiverAwarePlugins.remove(pluginClass);
      }

      // For ContentProviderAware plugins, notify the plugin that it is detached from
      // a ContentProvider if a ContentProvider is currently attached to this engine. Then
      // remove the plugin from our set of ContentProviderAware plugins.
      if (plugin instanceof ContentProviderAware) {
        if (isAttachedToContentProvider()) {
          ContentProviderAware contentProviderAware = (ContentProviderAware) plugin;
          contentProviderAware.onDetachedFromContentProvider();
        }
        contentProviderAwarePlugins.remove(pluginClass);
      }

      // Notify the plugin that is now detached from this engine. Then remove
      // it from our set of generic plugins.
      plugin.onDetachedFromEngine(pluginBinding);
      plugins.remove(pluginClass);
    }
  }

  public void remove(@NonNull Set<Class<? extends FlutterPlugin>> pluginClasses) {
    for (Class<? extends FlutterPlugin> pluginClass : pluginClasses) {
      remove(pluginClass);
    }
  }

  public void removeAll() {
    // We copy the keys to a new set so that we can mutate the set while using
    // the keys.
    remove(new HashSet<>(plugins.keySet()));
    plugins.clear();
  }

  private void detachFromAndroidComponent() {
    if (isAttachedToActivity()) {
      detachFromActivity();
    } else if (isAttachedToService()) {
      detachFromService();
    } else if (isAttachedToBroadcastReceiver()) {
      detachFromBroadcastReceiver();
    } else if (isAttachedToContentProvider()) {
      detachFromContentProvider();
    }
  }

  //-------- Start ActivityControlSurface -------
  private boolean isAttachedToActivity() {
    return activity != null;
  }

  @Override
  public void attachToActivity(@NonNull Activity activity, @NonNull Lifecycle lifecycle) {
    Log.d(TAG, "Attaching to an Activity.");
    // If we were already attached to an Android component, detach from it.
    detachFromAndroidComponent();

    this.activity = activity;
    this.activityPluginBinding = new FlutterEngineActivityPluginBinding(activity);
    this.flutterEngineAndroidLifecycle.setBackingLifecycle(lifecycle);

    // Notify all ActivityAware plugins that they are now attached to a new Activity.
    for (ActivityAware activityAware : activityAwarePlugins.values()) {
      activityAware.onAttachedToActivity(activityPluginBinding);
    }
  }

  @Override
  public void detachFromActivityForConfigChanges() {
    Log.d(TAG, "Detaching from an Activity for config changes.");
    if (isAttachedToActivity()) {
      for (ActivityAware activityAware : activityAwarePlugins.values()) {
        activityAware.onDetachedFromActivityForConfigChanges();
      }

      flutterEngineAndroidLifecycle.setBackingLifecycle(null);
      activity = null;
      activityPluginBinding = null;
    } else {
      Log.e(TAG, "Attempted to detach plugins from an Activity when no Activity was attached.");
    }
  }

  @Override
  public void reattachToActivityAfterConfigChange(@NonNull Activity activity, @NonNull Lifecycle lifecycle) {
    Log.d(TAG, "Re-attaching to an Activity after config change.");
    if (!isAttachedToActivity()) {
      this.activity = activity;
      activityPluginBinding = new FlutterEngineActivityPluginBinding(activity);
      this.flutterEngineAndroidLifecycle.setBackingLifecycle(lifecycle);

      for (ActivityAware activityAware : activityAwarePlugins.values()) {
        activityAware.onReattachedToActivityForConfigChanges(activityPluginBinding);
      }
    } else {
      Log.e(TAG, "Attempted to reattach plugins to an Activity after config changes, but an Activity was already attached.");
    }
  }

  @Override
  public void detachFromActivity() {
    Log.d(TAG, "Detaching from an Activity.");
    if (isAttachedToActivity()) {
      for (ActivityAware activityAware : activityAwarePlugins.values()) {
        activityAware.onDetachedFromActivity();
      }

      flutterEngineAndroidLifecycle.setBackingLifecycle(null);
      activity = null;
      activityPluginBinding = null;
    } else {
      Log.e(TAG, "Attempted to detach plugins from an Activity when no Activity was attached.");
    }
  }

  @Override
  public boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResult) {
    Log.d(TAG, "Forwarding onRequestPermissionsResult() to plugins.");
    if (isAttachedToActivity()) {
      return activityPluginBinding.onRequestPermissionsResult(requestCode, permissions, grantResult);
    } else {
      Log.e(TAG, "Attempted to notify ActivityAware plugins of onRequestPermissionsResult, but no Activity was attached.");
      return false;
    }
  }

  @Override
  public boolean onActivityResult(int requestCode, int resultCode, @NonNull Intent data) {
    Log.d(TAG, "Forwarding onActivityResult() to plugins.");
    if (isAttachedToActivity()) {
      return activityPluginBinding.onActivityResult(requestCode, resultCode, data);
    } else {
      Log.e(TAG, "Attempted to notify ActivityAware plugins of onActivityResult, but no Activity was attached.");
      return false;
    }
  }

  @Override
  public void onNewIntent(@NonNull Intent intent) {
    Log.d(TAG, "Forwarding onNewIntent() to plugins.");
    if (isAttachedToActivity()) {
      activityPluginBinding.onNewIntent(intent);
    } else {
      Log.e(TAG, "Attempted to notify ActivityAware plugins of onNewIntent, but no Activity was attached.");
    }
  }

  @Override
  public void onUserLeaveHint() {
    Log.d(TAG, "Forwarding onUserLeaveHint() to plugins.");
    if (isAttachedToActivity()) {
      activityPluginBinding.onUserLeaveHint();
    } else {
      Log.e(TAG, "Attempted to notify ActivityAware plugins of onUserLeaveHint, but no Activity was attached.");
    }
  }
  //------- End ActivityControlSurface -----

  //----- Start ServiceControlSurface ----
  private boolean isAttachedToService() {
    return service != null;
  }

  @Override
  public void attachToService(@NonNull Service service, @NonNull Lifecycle lifecycle, boolean isForeground) {
    // If we were already attached to an Android component, detach from it.
    detachFromAndroidComponent();

    this.service = service;
    this.servicePluginBinding = new FlutterEngineServicePluginBinding(service);
    flutterEngineAndroidLifecycle.setBackingLifecycle(lifecycle);

    // Notify all ServiceAware plugins that they are now attached to a new Service.
    for (ServiceAware serviceAware : serviceAwarePlugins.values()) {
      serviceAware.onAttachedToService(servicePluginBinding);
    }
  }

  @Override
  public void detachFromService() {
    if (isAttachedToService()) {
      // Notify all ServiceAware plugins that they are no longer attached to a Service.
      for (ServiceAware serviceAware : serviceAwarePlugins.values()) {
        serviceAware.onDetachedFromService();
      }

      flutterEngineAndroidLifecycle.setBackingLifecycle(null);
      service = null;
      servicePluginBinding = null;
    } else {
      Log.e(TAG, "Attempted to detach plugins from a Service when no Service was attached.");
    }
  }

  @Override
  public void onMoveToForeground() {
    if (isAttachedToService()) {
      servicePluginBinding.onMoveToForeground();
    }
  }

  @Override
  public void onMoveToBackground() {
    if (isAttachedToService()) {
      servicePluginBinding.onMoveToBackground();
    }
  }
  //----- End ServiceControlSurface ---

  //----- Start BroadcastReceiverControlSurface ---
  private boolean isAttachedToBroadcastReceiver() {
    return broadcastReceiver != null;
  }

  @Override
  public void attachToBroadcastReceiver(@NonNull BroadcastReceiver broadcastReceiver, @NonNull Lifecycle lifecycle) {
    // If we were already attached to an Android component, detach from it.
    detachFromAndroidComponent();

    this.broadcastReceiver = broadcastReceiver;
    this.broadcastReceiverPluginBinding = new FlutterEngineBroadcastReceiverPluginBinding(broadcastReceiver);
    // TODO(mattcarroll): resolve possibility of different lifecycles between this and engine attachment

    // Notify all BroadcastReceiverAware plugins that they are now attached to a new BroadcastReceiver.
    for (BroadcastReceiverAware broadcastReceiverAware : broadcastReceiverAwarePlugins.values()) {
      broadcastReceiverAware.onAttachedToBroadcastReceiver(broadcastReceiverPluginBinding);
    }
  }

  @Override
  public void detachFromBroadcastReceiver() {
    if (isAttachedToBroadcastReceiver()) {
      // Notify all BroadcastReceiverAware plugins that they are no longer attached to a BroadcastReceiver.
      for (BroadcastReceiverAware broadcastReceiverAware : broadcastReceiverAwarePlugins.values()) {
        broadcastReceiverAware.onDetachedFromBroadcastReceiver();
      }
    } else {
      Log.e(TAG, "Attempted to detach plugins from a BroadcastReceiver when no BroadcastReceiver was attached.");
    }
  }
  //----- End BroadcastReceiverControlSurface ----

  //----- Start ContentProviderControlSurface ----
  private boolean isAttachedToContentProvider() {
    return contentProvider != null;
  }

  @Override
  public void attachToContentProvider(@NonNull ContentProvider contentProvider, @NonNull Lifecycle lifecycle) {
    // If we were already attached to an Android component, detach from it.
    detachFromAndroidComponent();

    this.contentProvider = contentProvider;
    this.contentProviderPluginBinding = new FlutterEngineContentProviderPluginBinding(contentProvider);
    // TODO(mattcarroll): resolve possibility of different lifecycles between this and engine attachment

    // Notify all ContentProviderAware plugins that they are now attached to a new ContentProvider.
    for (ContentProviderAware contentProviderAware : contentProviderAwarePlugins.values()) {
      contentProviderAware.onAttachedToContentProvider(contentProviderPluginBinding);
    }
  }

  @Override
  public void detachFromContentProvider() {
    if (isAttachedToContentProvider()) {
      // Notify all ContentProviderAware plugins that they are no longer attached to a ContentProvider.
      for (ContentProviderAware contentProviderAware : contentProviderAwarePlugins.values()) {
        contentProviderAware.onDetachedFromContentProvider();
      }
    } else {
      Log.e(TAG, "Attempted to detach plugins from a ContentProvider when no ContentProvider was attached.");
    }
  }
  //----- End ContentProviderControlSurface -----

  private static class FlutterEngineActivityPluginBinding implements ActivityPluginBinding {
    private final Activity activity;
    private final Set<io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener> onRequestPermissionsResultListeners = new HashSet<>();
    private final Set<io.flutter.plugin.common.PluginRegistry.ActivityResultListener> onActivityResultListeners = new HashSet<>();
    private final Set<io.flutter.plugin.common.PluginRegistry.NewIntentListener> onNewIntentListeners = new HashSet<>();
    private final Set<io.flutter.plugin.common.PluginRegistry.UserLeaveHintListener> onUserLeaveHintListeners = new HashSet<>();

    public FlutterEngineActivityPluginBinding(@NonNull Activity activity) {
      this.activity = activity;
    }

    /**
     * Returns the {@link Activity} that is currently attached to the {@link FlutterEngine} that
     * owns this {@code ActivityPluginBinding}.
     */
    @NonNull
    public Activity getActivity() {
      return activity;
    }

    /**
     * Adds a listener that is invoked whenever the associated {@link Activity}'s
     * {@code onRequestPermissionsResult(...)} method is invoked.
     */
    public void addRequestPermissionsResultListener(@NonNull io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener listener) {
      onRequestPermissionsResultListeners.add(listener);
    }

    /**
     * Removes a listener that was added in {@link #addRequestPermissionsResultListener(io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener)}.
     */
    public void removeRequestPermissionsResultListener(@NonNull io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener listener) {
      onRequestPermissionsResultListeners.remove(listener);
    }

    /**
     * Invoked by the {@link FlutterEngine} that owns this {@code ActivityPluginBinding} when its
     * associated {@link Activity} has its {@code onRequestPermissionsResult(...)} method invoked.
     */
    boolean onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResult) {
      boolean didConsumeResult = false;
      for (io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener listener : onRequestPermissionsResultListeners) {
        didConsumeResult = listener.onRequestPermissionsResult(requestCode, permissions, grantResult) || didConsumeResult;
      }
      return didConsumeResult;
    }

    /**
     * Adds a listener that is invoked whenever the associated {@link Activity}'s
     * {@code onActivityResult(...)} method is invoked.
     */
    public void addActivityResultListener(@NonNull io.flutter.plugin.common.PluginRegistry.ActivityResultListener listener) {
      onActivityResultListeners.add(listener);
    }

    /**
     * Removes a listener that was added in {@link #addActivityResultListener(io.flutter.plugin.common.PluginRegistry.ActivityResultListener)}.
     */
    public void removeActivityResultListener(@NonNull io.flutter.plugin.common.PluginRegistry.ActivityResultListener listener) {
      onActivityResultListeners.remove(listener);
    }

    /**
     * Invoked by the {@link FlutterEngine} that owns this {@code ActivityPluginBinding} when its
     * associated {@link Activity} has its {@code onActivityResult(...)} method invoked.
     */
    boolean onActivityResult(int requestCode, int resultCode, @NonNull Intent data) {
      boolean didConsumeResult = false;
      for (io.flutter.plugin.common.PluginRegistry.ActivityResultListener listener : onActivityResultListeners) {
        didConsumeResult = listener.onActivityResult(requestCode, resultCode, data) || didConsumeResult;
      }
      return didConsumeResult;
    }

    /**
     * Adds a listener that is invoked whenever the associated {@link Activity}'s
     * {@code onNewIntent(...)} method is invoked.
     */
    public void addOnNewIntentListener(@NonNull io.flutter.plugin.common.PluginRegistry.NewIntentListener listener) {
      onNewIntentListeners.add(listener);
    }

    /**
     * Removes a listener that was added in {@link #addOnNewIntentListener(io.flutter.plugin.common.PluginRegistry.NewIntentListener)}.
     */
    public void removeOnNewIntentListener(@NonNull io.flutter.plugin.common.PluginRegistry.NewIntentListener listener) {
      onNewIntentListeners.remove(listener);
    }

    /**
     * Invoked by the {@link FlutterEngine} that owns this {@code ActivityPluginBinding} when its
     * associated {@link Activity} has its {@code onNewIntent(...)} method invoked.
     */
    void onNewIntent(@Nullable Intent intent) {
      for (io.flutter.plugin.common.PluginRegistry.NewIntentListener listener : onNewIntentListeners) {
        listener.onNewIntent(intent);
      }
    }

    /**
     * Adds a listener that is invoked whenever the associated {@link Activity}'s
     * {@code onUserLeaveHint()} method is invoked.
     */
    public void addOnUserLeaveHintListener(@NonNull io.flutter.plugin.common.PluginRegistry.UserLeaveHintListener listener) {
      onUserLeaveHintListeners.add(listener);
    }

    /**
     * Removes a listener that was added in {@link #addOnUserLeaveHintListener(io.flutter.plugin.common.PluginRegistry.UserLeaveHintListener)}.
     */
    public void removeOnUserLeaveHintListener(@NonNull io.flutter.plugin.common.PluginRegistry.UserLeaveHintListener listener) {
      onUserLeaveHintListeners.remove(listener);
    }

    /**
     * Invoked by the {@link FlutterEngine} that owns this {@code ActivityPluginBinding} when its
     * associated {@link Activity} has its {@code onUserLeaveHint()} method invoked.
     */
    void onUserLeaveHint() {
      for (io.flutter.plugin.common.PluginRegistry.UserLeaveHintListener listener : onUserLeaveHintListeners) {
        listener.onUserLeaveHint();
      }
    }
  }

  private static class FlutterEngineServicePluginBinding implements ServicePluginBinding {
    private final Service service;
    private final Set<ServiceAware.OnModeChangeListener> onModeChangeListeners = new HashSet<>();

    FlutterEngineServicePluginBinding(@NonNull Service service) {
      this.service = service;
    }

    @NonNull
    @Override
    public Service getService() {
      return service;
    }

    @Override
    public void addOnModeChangeListener(@NonNull ServiceAware.OnModeChangeListener listener) {
      onModeChangeListeners.add(listener);
    }

    @Override
    public void removeOnModeChangeListener(@NonNull ServiceAware.OnModeChangeListener listener) {
      onModeChangeListeners.remove(listener);
    }

    void onMoveToForeground() {
      for (ServiceAware.OnModeChangeListener listener : onModeChangeListeners) {
        listener.onMoveToForeground();
      }
    }

    void onMoveToBackground() {
      for (ServiceAware.OnModeChangeListener listener : onModeChangeListeners) {
        listener.onMoveToBackground();
      }
    }
  }

  private static class FlutterEngineBroadcastReceiverPluginBinding implements BroadcastReceiverPluginBinding {
    private final BroadcastReceiver broadcastReceiver;

    FlutterEngineBroadcastReceiverPluginBinding(@NonNull BroadcastReceiver broadcastReceiver) {
      this.broadcastReceiver = broadcastReceiver;
    }

    @NonNull
    @Override
    public BroadcastReceiver getBroadcastReceiver() {
      return broadcastReceiver;
    }
  }

  private static class FlutterEngineContentProviderPluginBinding implements ContentProviderPluginBinding {
    private final ContentProvider contentProvider;

    FlutterEngineContentProviderPluginBinding(@NonNull ContentProvider contentProvider) {
      this.contentProvider = contentProvider;
    }

    @NonNull
    @Override
    public ContentProvider getContentProvider() {
      return contentProvider;
    }
  }
}
