/*******************************************************************************
 *                                                                             *
 *  Copyright (C) 2017 by Max Lv <max.c.lv@gmail.com>                          *
 *  Copyright (C) 2017 by Mygod Studio <contact-shadowsocks-android@mygod.be>  *
 *                                                                             *
 *  This program is free software: you can redistribute it and/or modify       *
 *  it under the terms of the GNU General Public License as published by       *
 *  the Free Software Foundation, either version 3 of the License, or          *
 *  (at your option) any later version.                                        *
 *                                                                             *
 *  This program is distributed in the hope that it will be useful,            *
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of             *
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the              *
 *  GNU General Public License for more details.                               *
 *                                                                             *
 *  You should have received a copy of the GNU General Public License          *
 *  along with this program. If not, see <http://www.gnu.org/licenses/>.       *
 *                                                                             *
 *******************************************************************************/

package com.github.shadowsocks

import android.app.Activity
import android.app.backup.BackupManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.RemoteException
import android.util.Log
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.browser.customtabs.CustomTabColorSchemeParams
import androidx.browser.customtabs.CustomTabsIntent
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.drawable.toAdaptiveIcon
import androidx.core.graphics.drawable.toIcon
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.updateLayoutParams
import androidx.drawerlayout.widget.DrawerLayout
import androidx.preference.PreferenceDataStore
import com.crashlytics.android.Crashlytics
import com.github.shadowsocks.aidl.IShadowsocksService
import com.github.shadowsocks.aidl.ShadowsocksConnection
import com.github.shadowsocks.aidl.TrafficStats
import com.github.shadowsocks.bg.BaseService
import com.github.shadowsocks.preference.DataStore
import com.github.shadowsocks.preference.OnPreferenceDataStoreChangeListener
import com.github.shadowsocks.utils.Key
import com.github.shadowsocks.utils.SingleInstanceActivity
import com.github.shadowsocks.widget.ListHolderListener
import com.github.shadowsocks.widget.ServiceButton
import com.github.shadowsocks.widget.StatsBar
import com.google.android.material.navigation.NavigationView
import com.google.android.material.snackbar.Snackbar

class MainActivity : AppCompatActivity(), ShadowsocksConnection.Callback, OnPreferenceDataStoreChangeListener,
        NavigationView.OnNavigationItemSelectedListener {
    companion object {
        private const val TAG = "ShadowsocksMainActivity"
        private const val REQUEST_CONNECT = 1

        var stateListener: ((BaseService.State) -> Unit)? = null
    }

    // UI
    private lateinit var fab: ServiceButton
    private lateinit var stats: StatsBar
    internal lateinit var drawer: DrawerLayout
    private lateinit var navigation: NavigationView

    lateinit var snackbar: CoordinatorLayout private set
    fun snackbar(text: CharSequence = "") = Snackbar.make(snackbar, text, Snackbar.LENGTH_LONG).apply {
        anchorView = fab
    }

    private val customTabsIntent by lazy {
        CustomTabsIntent.Builder().apply {
            setColorScheme(CustomTabsIntent.COLOR_SCHEME_SYSTEM)
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_LIGHT, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.light_color_primary))
            }.build())
            setColorSchemeParams(CustomTabsIntent.COLOR_SCHEME_DARK, CustomTabColorSchemeParams.Builder().apply {
                setToolbarColor(ContextCompat.getColor(this@MainActivity, R.color.dark_color_primary))
            }.build())
        }.build()
    }
    fun launchUrl(uri: String) = try {
        customTabsIntent.launchUrl(this, uri.toUri())
    } catch (_: ActivityNotFoundException) {
        snackbar(uri).show()
    }

    // service
    var state = BaseService.State.Idle
    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) =
            changeState(state, msg, true)
    override fun trafficUpdated(profileId: Long, stats: TrafficStats) {
        if (profileId == 0L) this@MainActivity.stats.updateTraffic(
                stats.txRate, stats.rxRate, stats.txTotal, stats.rxTotal)
        if (state != BaseService.State.Stopping) {
            (supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ProfilesFragment)
                    ?.onTrafficUpdated(profileId, stats)
        }
    }
    override fun trafficPersisted(profileId: Long) {
        ProfilesFragment.instance?.onTrafficPersisted(profileId)
    }

    private fun changeState(state: BaseService.State, msg: String? = null, animate: Boolean = false) {
        fab.changeState(state, this.state, animate)
        stats.changeState(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
        this.state = state
        ProfilesFragment.instance?.profilesAdapter?.notifyDataSetChanged()  // refresh button enabled state
        stateListener?.invoke(state)
    }

    private fun toggle() = when {
        state.canStop -> Core.stopService()
        DataStore.serviceMode == Key.modeVpn -> {
            val intent = VpnService.prepare(this)
            if (intent != null) startActivityForResult(intent, REQUEST_CONNECT)
            else onActivityResult(REQUEST_CONNECT, Activity.RESULT_OK, null)
        }
        else -> Core.startService()
    }

    private fun updateShortcuts() {
        fun getBitmap(id: Int): Bitmap {
            var drawable = AppCompatResources.getDrawable(this, id)!!
            if (drawable is BitmapDrawable)
                return drawable.bitmap
            if (Build.VERSION.SDK_INT >= 26 && drawable is AdaptiveIconDrawable) {
                drawable = LayerDrawable(arrayOf(drawable.background, drawable.foreground))
            }
            val bitmap = Bitmap.createBitmap(
                    drawable.intrinsicWidth, drawable.intrinsicHeight,
                    Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        fun getIcon(id: Int): Icon {
            return if (Build.VERSION.SDK_INT >= 26)
                getBitmap(id).toAdaptiveIcon()
            else
                getBitmap(id).toIcon()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            val shortcutManager = getSystemService<ShortcutManager>(ShortcutManager::class.java)
            val toggle = ShortcutInfoCompat.Builder(this, Shortcut.SHORTCUT_TOGGLE)
                    .setIntent(Intent(this, Shortcut::class.java).setAction(Shortcut.SHORTCUT_TOGGLE))
                    .setIcon(IconCompat.createFromIcon(this, getIcon(R.drawable.ic_qu_shadowsocks_launcher)))
                    .setShortLabel(Core.app.getString(R.string.quick_toggle))
                    .build()
                    .toShortcutInfo()
            val scan = ShortcutInfoCompat.Builder(this, Shortcut.SHORTCUT_SCAN)
                    .setIntent(Intent(this, Shortcut::class.java).setAction(Shortcut.SHORTCUT_SCAN))
                    .setIcon(IconCompat.createFromIcon(this, getIcon(R.drawable.ic_qu_camera_launcher)))
                    .setShortLabel(Core.app.getString(R.string.add_profile_methods_scan_qr_code))
                    .build()
                    .toShortcutInfo()
            shortcutManager?.dynamicShortcuts = listOf(toggle, scan)
        }
    }

    private val handler = Handler()
    private val connection = ShadowsocksConnection(handler, true)
    override fun onServiceConnected(service: IShadowsocksService) = changeState(try {
        BaseService.State.values()[service.state]
    } catch (_: RemoteException) {
        BaseService.State.Idle
    })
    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when {
            requestCode != REQUEST_CONNECT -> super.onActivityResult(requestCode, resultCode, data)
            resultCode == Activity.RESULT_OK -> Core.startService()
            else -> {
                snackbar().setText(R.string.vpn_permission_denied).show()
                Crashlytics.log(Log.ERROR, TAG, "Failed to start VpnService from onActivityResult: $data")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SingleInstanceActivity.register(this) ?: return
        setContentView(R.layout.layout_main)
        snackbar = findViewById(R.id.snackbar)
        snackbar.setOnApplyWindowInsetsListener(ListHolderListener)
        stats = findViewById(R.id.stats)
        stats.setOnClickListener { if (state == BaseService.State.Connected) stats.testConnection() }
        drawer = findViewById(R.id.drawer)
        drawer.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        navigation = findViewById(R.id.navigation)
        navigation.setNavigationItemSelectedListener(this)
        if (savedInstanceState == null) {
            navigation.menu.findItem(R.id.profiles).isChecked = true
            displayFragment(ProfilesFragment())
        }

        fab = findViewById(R.id.fab)
        fab.setOnClickListener { toggle() }
        fab.setOnApplyWindowInsetsListener { view, insets ->
            view.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = insets.systemWindowInsetBottom +
                        resources.getDimensionPixelOffset(R.dimen.mtrl_bottomappbar_fab_bottom_margin)
            }
            insets
        }

        changeState(BaseService.State.Idle) // reset everything to init state
        connection.connect(this, this)
        DataStore.publicStore.registerChangeListener(this)
        updateShortcuts()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        when (key) {
            Key.serviceMode -> handler.post {
                connection.disconnect(this)
                connection.connect(this, this)
            }
        }
    }

    private fun displayFragment(fragment: ToolbarFragment) {
        supportFragmentManager.beginTransaction().replace(R.id.fragment_holder, fragment).commitAllowingStateLoss()
        drawer.closeDrawers()
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        if (item.isChecked) drawer.closeDrawers() else {
            when (item.itemId) {
                R.id.profiles -> {
                    displayFragment(ProfilesFragment())
                    connection.bandwidthTimeout = connection.bandwidthTimeout   // request stats update
                }
                R.id.globalSettings -> displayFragment(GlobalSettingsFragment())
                R.id.about -> {
                    displayFragment(AboutFragment())
                }
                R.id.faq -> {
                    launchUrl(getString(R.string.faq_url))
                    return true
                }
                else -> return false
            }
            item.isChecked = true
        }
        return true
    }

    override fun onStart() {
        super.onStart()
        connection.bandwidthTimeout = 500
    }

    override fun onBackPressed() {
        if (drawer.isDrawerOpen(GravityCompat.START)) drawer.closeDrawers() else {
            val currentFragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment
            if (!currentFragment.onBackPressed()) {
                if (currentFragment is ProfilesFragment) super.onBackPressed() else {
                    navigation.menu.findItem(R.id.profiles).isChecked = true
                    displayFragment(ProfilesFragment())
                }
            }
        }
    }

    override fun onKeyShortcut(keyCode: Int, event: KeyEvent) = when {
        keyCode == KeyEvent.KEYCODE_G && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            toggle()
            true
        }
        keyCode == KeyEvent.KEYCODE_T && event.hasModifiers(KeyEvent.META_CTRL_ON) -> {
            stats.testConnection()
            true
        }
        else -> (supportFragmentManager.findFragmentById(R.id.fragment_holder) as ToolbarFragment).toolbar.menu.let {
            it.setQwertyMode(KeyCharacterMap.load(event.deviceId).keyboardType != KeyCharacterMap.NUMERIC)
            it.performShortcut(keyCode, event, 0)
        }
    }

    override fun onStop() {
        connection.bandwidthTimeout = 0
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        DataStore.publicStore.unregisterChangeListener(this)
        connection.disconnect(this)
        BackupManager(this).dataChanged()
        handler.removeCallbacksAndMessages(null)
    }
}
