package com.morpheusdata.reports

import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.core.data.DataQuery
import com.morpheusdata.core.providers.AbstractAnalyticsProvider
import com.morpheusdata.model.User
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel

class CustomAnalyticsProvider extends AbstractAnalyticsProvider {
	Plugin plugin
	MorpheusContext morpheusContext

	CustomAnalyticsProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	String getCategory() {
		return "test"
	}

	@Override
	String getDescription() {
		return "A Test Analytics Provider"
	}

	@Override
	Boolean getMasterTenantOnly() {
		return false
	}

	@Override
	Boolean getSubTenantOnly() {
		return false
	}

	@Override
	Integer getDisplayOrder() {
		return 0
	}

	@Override
	ServiceResponse<Map<String, Object>> loadData(User user, Map<String, Object> map) {
		def userList = morpheus.services.admin.user.list(new DataQuery(account: user.account))
		def data = [username: user.username, email: user.email, list: userList]
		return ServiceResponse.success(data)
	}

	@Override
	HTMLResponse renderTemplate(User user, Map<String, Object> data, Map<String, Object> params) {
		ViewModel<String> model = new ViewModel<String>()
		model.object = data
		getRenderer().renderTemplate("hbs/sampleAnalytics", model)
	}

	@Override
	MorpheusContext getMorpheus() {
		return morpheusContext
	}

	@Override
	String getCode() {
		return "analytics-test"
	}

	@Override
	String getName() {
		return "Test Analytics"
	}
}
