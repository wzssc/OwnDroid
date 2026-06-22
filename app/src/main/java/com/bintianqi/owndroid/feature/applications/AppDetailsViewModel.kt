package com.bintianqi.owndroid.feature.applications

import android.os.Build.VERSION
import androidx.annotation.RequiresApi
import androidx.lifecycle.ViewModel
import com.bintianqi.owndroid.MyApplication
import com.bintianqi.owndroid.PrivilegeHelper
import com.bintianqi.owndroid.utils.PrivilegeStatus
import com.bintianqi.owndroid.utils.ToastChannel
import com.bintianqi.owndroid.utils.getAppInfo
import com.bintianqi.owndroid.utils.plusOrMinus
import com.bintianqi.owndroid.utils.runtimePermissions
import com.bintianqi.owndroid.utils.uninstallPackage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

class AppDetailsViewModel(
    val packageName: String, val application: MyApplication, val ph: PrivilegeHelper,
    val privilegeState: StateFlow<PrivilegeStatus>, val toastChannel: ToastChannel
) : ViewModel() {
    val appInfo = getAppInfo(application.packageManager, packageName)
    val uiState = MutableStateFlow(AppDetailsUiState())

    init {
        getStatus()
    }

    fun getStatus() = ph.safeDpmCall {
        uiState.value = AppDetailsUiState(
            if (VERSION.SDK_INT >= 24) dpm.isPackageSuspended(dar, packageName) else false,
            dpm.isApplicationHidden(dar, packageName),
            dpm.isUninstallBlocked(dar, packageName),
            if (VERSION.SDK_INT >= 30) packageName in dpm.getUserControlDisabledPackages(dar)
            else false,
            if (VERSION.SDK_INT >= 28) packageName in dpm.getMeteredDataDisabledPackages(dar)
            else false,
            if (VERSION.SDK_INT >= 28 && privilegeState.value.device)
                dpm.getKeepUninstalledPackages(dar)?.contains(packageName) == true
            else false
        )
    }

    @RequiresApi(24)
    fun setSuspended(status: Boolean) = ph.safeDpmCall {
        try {
            dpm.setPackagesSuspended(dar, arrayOf(packageName), status)
            uiState.update { it.copy(suspend = dpm.isPackageSuspended(dar, packageName)) }
        } catch (_: Exception) {
        }
    }

    fun setHidden(status: Boolean) = ph.safeDpmCall {
        dpm.setApplicationHidden(dar, packageName, status)
        uiState.update { it.copy(hide = dpm.isApplicationHidden(dar, packageName)) }
    }

    fun setUninstallBlocked(status: Boolean) = ph.safeDpmCall {
        dpm.setUninstallBlocked(dar, packageName, status)
        uiState.update { it.copy(uninstallBlocked = dpm.isUninstallBlocked(dar, packageName)) }
    }

    @RequiresApi(30)
    fun setUserControlDisabled(state: Boolean) = ph.safeDpmCall {
        dpm.setUserControlDisabledPackages(
            dar,
            dpm.getUserControlDisabledPackages(dar).plusOrMinus(state, packageName)
        )
        uiState.update {
            it.copy(userControlDisabled = packageName in dpm.getUserControlDisabledPackages(dar))
        }
    }

    @RequiresApi(28)
    fun setMeteredDataDisabled(state: Boolean) = ph.safeDpmCall {
        dpm.setMeteredDataDisabledPackages(
            dar,
            dpm.getMeteredDataDisabledPackages(dar).plusOrMinus(state, packageName)
        )
        uiState.update {
            it.copy(meteredDataDisabled = packageName in dpm.getMeteredDataDisabledPackages(dar))
        }
    }

    @RequiresApi(28)
    fun setKeepUninstalled(state: Boolean) = ph.safeDpmCall {
        dpm.setKeepUninstalledPackages(
            dar,
            (dpm.getKeepUninstalledPackages(dar) ?: emptyList()).plusOrMinus(state, packageName)
        )
        uiState.update {
            it.copy(
                keepUninstalled = dpm.getKeepUninstalledPackages(dar)?.contains(packageName) == true
            )
        }
    }

    val permissionsState = MutableStateFlow(emptyMap<String, Int>())

    fun getPermissions() = ph.safeDpmCall {
        permissionsState.value = runtimePermissions.associate {
            it.id to dpm.getPermissionGrantState(dar, packageName, it.id)
        }
    }

    fun setPermission(permission: String, status: Int) = ph.safeDpmCall {
        val result = dpm.setPermissionGrantState(dar, packageName, permission, status)
        if (result) {
            getPermissions()
        } else {
            toastChannel.sendStatus(false)
        }
    }

    @RequiresApi(28)
    fun clearData(callback: () -> Unit) = ph.safeDpmCall {
        dpm.clearApplicationUserData(dar, packageName, application.mainExecutor) { _, result ->
            callback()
            toastChannel.sendStatus(result)
        }
    }

    fun uninstall(callback: (String?) -> Unit) {
        // 1. 赛博第一刀：直接抛底层 Shell 命令尝试剥离角色（无任何 API 依赖）
        try {
            Runtime.getRuntime().exec("cmd role remove android.app.role.BROWSER ${packageName}")
        } catch (e: Exception) {
            // 忽略所有系统拦截或错误
        }

        // 2. 赛博第二刀：执行隐藏逻辑
        ph.safeDpmCall {
            try {
                val success = dpm.setApplicationHidden(dar, packageName, true)
                if (success) {
                    uiState.update { it.copy(hide = dpm.isApplicationHidden(dar, packageName)) }
                    callback(null)
                } else {
                    uninstallPackage(application, ph, packageName, callback)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                uninstallPackage(application, ph, packageName, callback)
            }
        } ?: run {
            uninstallPackage(application, ph, packageName, callback)
        }
    }
}
