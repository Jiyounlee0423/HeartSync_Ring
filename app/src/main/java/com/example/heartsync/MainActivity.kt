// app/src/main/java/com/example/heartsync/MainActivity.kt
package com.example.heartsync

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navigation
import com.example.heartsync.ui.components.BottomBar
import com.example.heartsync.ui.components.TopBar
import com.example.heartsync.ui.screens.BleConnectScreen
import com.example.heartsync.ui.screens.DataVizScreen
import com.example.heartsync.ui.screens.HomeScreen
import com.example.heartsync.ui.screens.LoginScreen
import com.example.heartsync.ui.screens.MeasureScreen
import com.example.heartsync.ui.screens.NotiLogScreen
import com.example.heartsync.ui.screens.RegisterScreen
import com.example.heartsync.ui.screens.SplashSequence
import com.example.heartsync.ui.screens.UserInfoScreen
import com.example.heartsync.ui.themes.HeartSyncTheme
import com.example.heartsync.util.Route
import com.example.heartsync.viewmodel.AuthViewModel
import com.example.heartsync.viewmodel.BleViewModel
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // ★ Activity 범위에서 단 하나의 BLE ViewModel 생성(앱 전체 공유)
    private val bleVm: BleViewModel by viewModels()

    private val permissionLauncher =
        registerForActivityResult(RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                runCatching { auth.signInAnonymously().await() }
                    .onSuccess {
                        android.util.Log.d("Auth", "Anonymous sign-in OK: uid=${auth.currentUser?.uid}")
                    }
                    .onFailure { e ->
                        android.util.Log.e("Auth", "Anonymous sign-in FAILED", e)
                        return@launch
                    }
            } else {
                android.util.Log.d("Auth", "Already signed in: uid=${auth.currentUser?.uid}")
            }

            val app = FirebaseApp.getInstance()
            android.util.Log.d("HeartSyncInit",
                "projectId=${app.options.projectId}, appId=${app.options.applicationId}")

            val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return@launch
            val ref = FirebaseFirestore.getInstance()
                .collection("users").document(uid)
                .collection("health").document("ping")

            runCatching {
                ref.set(mapOf("ok" to true, "at" to System.currentTimeMillis())).await()
            }.onSuccess {
                android.util.Log.d("Firestore", "TEST WRITE OK")
            }.onFailure { t ->
                android.util.Log.e("Firestore", "TEST WRITE FAIL", t)
            }
        }

        setContent {
            HeartSyncTheme {
                val nav = rememberNavController()
                val authVm: AuthViewModel = viewModel()

                // 현재 라우트
                val backStackEntry by nav.currentBackStackEntryAsState()
                val currentDest = backStackEntry?.destination
                val currentRoute = currentDest?.route

                // TopBar: Splash에서는 숨김
                val showTopBar = (currentRoute ?: Route.Splash) != Route.Splash

                // BottomBar: 로그인/회원가입/스플래시에서는 숨김 + 탭 화면에서만 표시
                val bottomBarRoutes = remember {
                    setOf(
                        Route.MAIN,
                        Route.Home,
                        Route.Profile,
                        Route.Docs,
                        Route.Noti,
                        // Route.BLE_CONNECT 는 하단바에 노출하지 않음
                    )
                }
                val showBottomBar = currentDest
                    ?.hierarchy
                    ?.any { d -> d.route != null && d.route in bottomBarRoutes } == true

                Scaffold(
                    topBar = { if (showTopBar) TopBar() },
                    bottomBar = { if (showBottomBar) BottomBar(nav) }
                ) { inner ->
                    AppNav(
                        navController = nav,
                        modifier = Modifier.padding(inner),
                        authVm = authVm,
                        bleVm = bleVm
                    )
                }
            }
        }
    }

    /**
     * 비로그인(또는 익명)일 때 보호 라우트 접근을 막는 간단 가드
     */
    @Composable
    private fun RequireAuth(
        nav: NavHostController,
        content: @Composable () -> Unit
    ) {
        val user = FirebaseAuth.getInstance().currentUser
        val isLoggedIn = user != null && !user.isAnonymous
        LaunchedEffect(isLoggedIn) {
            if (!isLoggedIn) {
                nav.navigate(Route.Login) {
                    popUpTo(nav.graph.findStartDestination().id) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
        if (isLoggedIn) content()
    }

    @Composable
    private fun AppNav(
        navController: NavHostController,
        modifier: Modifier = Modifier,
        authVm: AuthViewModel,
        bleVm: BleViewModel,                 // ★ 전달받은 전역 BLE VM
    ) {
        // 익명은 로그인으로 취급하지 않음
        val cur = FirebaseAuth.getInstance().currentUser
        val isLoggedIn = cur != null && !cur.isAnonymous
        val nextRoute = if (isLoggedIn) Route.MAIN else Route.Login

        NavHost(
            navController = navController,
            startDestination = Route.Splash,
            route = Route.ROOT,
            modifier = modifier
        ) {
            // 1) 스플래시
            composable(Route.Splash) {
                SplashSequence(nextRoute = nextRoute) { route ->
                    navController.navigate(route) {
                        popUpTo(Route.Splash) { inclusive = true }
                        launchSingleTop = true
                    }
                }
            }

            // 2) 인증
            composable(Route.Login) { LoginScreen(nav = navController, vm = authVm) }
            composable(Route.Register) { RegisterScreen(nav = navController, vm = authVm) }

            // 3) 메인 그래프 (보호 라우트)
            navigation(startDestination = Route.Home, route = Route.MAIN) {

                composable(Route.Home) {
                    RequireAuth(navController) {
                        HomeScreen(
                            onClickBle = { navController.navigate(Route.BLE_CONNECT) },
                            bleVm = bleVm,
                            onStartMeasure = {
                                navController.navigate(Route.Measure)
                            }
                        )
                    }
                }

                composable(Route.BLE_CONNECT) {
                    RequireAuth(navController) {
                        BleConnectScreen(
                            vm = bleVm,
                            onConnected = { navController.popBackStack() }
                        )
                    }
                }

                // MainActivity.kt (NavHost 정의 부분 중 Measure route)
                composable(Route.Measure) {
                    MeasureScreen(
                        onFinish = {
                            // 1) 먼저 Home까지 pop (Home이 스택에 있으면 true)
                            val ok = navController.popBackStack(Route.Home, false)

                            // 2) Home이 스택에 없으면 새로 네비게이트
                            if (!ok) {
                                navController.navigate(Route.Home) {
                                    launchSingleTop = true   // Home가 위에 또 쌓이지 않도록
                                }
                            }
                        }
                    )
                }

                composable(Route.Profile) {
                    RequireAuth(navController) {
                        UserInfoScreen(
                            onLogout = {
                                authVm.logout()
                                navController.navigate(Route.Login) {
                                    popUpTo(0) { inclusive = true } // ★ 전체 스택 정리
                                    launchSingleTop = true
                                }
                            }

                        )
                    }
                }

                composable(Route.Docs) {
                    RequireAuth(navController) { DataVizScreen() }
                }

                composable(Route.Noti) {
                    RequireAuth(navController) { NotiLogScreen() }
                }
            }
        }
    }

    private fun requestRuntimePerms() {
        val perms = buildList {
            if (Build.VERSION.SDK_INT >= 31) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }
}
