package com.plogging.ecorun.ui.main

import android.os.Bundle
import android.view.View
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.plogging.ecorun.R
import com.plogging.ecorun.base.BaseFragment
import com.plogging.ecorun.databinding.FragmentMainBinding
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainFragment : BaseFragment<FragmentMainBinding, MainViewModel>() {
    override fun getViewBinding() = FragmentMainBinding.inflate(layoutInflater)
    override val viewModel: MainViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        showBottomView()
        setBottomNav()
    }

    private fun showBottomView() {
        viewModel.showBottomNav.observe(viewLifecycleOwner) { isShow ->
            when (isShow) {
                null -> binding.clMain.transitionToStart()
                true -> binding.clMain.transitionToStart()
                false -> binding.clMain.transitionToEnd()
            }
        }
    }

    private fun setBottomNav() {
        binding.bottomNav.itemIconTintList = null
        val navHostFragment =
            childFragmentManager.findFragmentById(R.id.nav_host_container) as NavHostFragment
        val navController = navHostFragment.navController
        // argument를 넘기기 위해서는 xml에 graph를 지정하지 않고 코드를 직접 작성한다.
        navController.setGraph(R.navigation.nav_main, arguments)
        // 반드시 navigation id와 menu id가 같아야한다.
        binding.bottomNav.setupWithNavController(navController)
        var selectedId = binding.bottomNav.selectedItemId
        binding.bottomNav.setOnItemSelectedListener {
            if (selectedId != it.itemId) {
                selectedId = it.itemId
                navController.navigate(it.itemId, arguments)
                true
            } else false
        }
    }

    override fun clickListener() {}
}