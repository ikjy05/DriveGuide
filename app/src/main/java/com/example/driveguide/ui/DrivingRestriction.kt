package com.example.driveguide.ui

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 주행 중 화면 제한(CarUxRestrictions) 상태를 알려준다. 주행 중이면 true.
 * 차량이 아닌 환경에서는 제한 없음(false)으로 동작한다.
 */
class DrivingRestriction(private val context: Context, private val onChange: (Boolean) -> Unit) {

    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun start() {
        try {
            car = Car.createCar(context)
            manager = car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
            manager?.registerListener { restrictions ->
                mainHandler.post { onChange(restrictions.isRequiresDistractionOptimization) }
            }
            onChange(manager?.currentCarUxRestrictions?.isRequiresDistractionOptimization ?: false)
        } catch (e: Throwable) {
            Log.w(TAG, "Car UX restrictions unavailable", e)
            onChange(false)
        }
    }

    fun stop() {
        runCatching {
            manager?.unregisterListener()
            car?.disconnect()
        }
    }

    companion object {
        private const val TAG = "DrivingRestriction"
    }
}
