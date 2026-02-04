package com.morpheusdata.reports

import com.morpheusdata.core.Plugin

class ReportsPlugin extends Plugin {

	@Override
	String getCode() {
		return 'morpheus-reports-plugin'
	}

	@Override
	void initialize() {
		this.setName("Custom Reports")

		CustomReportProvider customReportProvider = new CustomReportProvider(this, morpheus)
		// CustomAnalyticsProvider customAnalyticsProvider = new CustomAnalyticsProvider(this, morpheus)

		this.pluginProviders.put(customReportProvider.code, customReportProvider)
		// this.pluginProviders.put(customAnalyticsProvider.code, customAnalyticsProvider)
	}

	@Override
	void onDestroy() {
	}
}
