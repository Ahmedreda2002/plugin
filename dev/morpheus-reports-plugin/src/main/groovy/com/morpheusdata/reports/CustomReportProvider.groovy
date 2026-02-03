package com.morpheusdata.reports

import com.morpheusdata.core.AbstractReportProvider
import com.morpheusdata.core.MorpheusContext
import com.morpheusdata.core.Plugin
import com.morpheusdata.model.ContentSecurityPolicy
import com.morpheusdata.model.OptionType
import com.morpheusdata.model.ReportResult
import com.morpheusdata.model.ReportResultRow
import com.morpheusdata.response.ServiceResponse
import com.morpheusdata.views.HTMLResponse
import com.morpheusdata.views.ViewModel
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.util.logging.Slf4j
import io.reactivex.rxjava3.core.Observable

import java.sql.Connection

@Slf4j
class CustomReportProvider extends AbstractReportProvider {

	Plugin plugin
	MorpheusContext morpheusContext

	CustomReportProvider(Plugin plugin, MorpheusContext context) {
		this.plugin = plugin
		this.morpheusContext = context
	}

	@Override
	MorpheusContext getMorpheus() { morpheusContext }

	@Override
	Plugin getPlugin() { plugin }

	@Override
	String getCode() { 'custom-report-instance-status' }

	@Override
	String getName() { 'Client Cloud VM Report' }

	@Override
	String getDescription() {
		return "One client per report. Shows VM Name, vCPU cores, RAM, Storage, and ON/OFF status."
	}

	@Override
	String getCategory() { 'inventory' }

	@Override
	Boolean getOwnerOnly() { false }

	@Override
	Boolean getMasterOnly() { true }

	@Override
	Boolean getSupportsAllZoneTypes() { true }

	@Override
	ServiceResponse validateOptions(Map opts) {
		def cloudId = opts?.config?.cloudId ?: opts?.cloudId
		if(!cloudId) return ServiceResponse.error("Client Cloud is required")
		return ServiceResponse.success()
	}

	@Override
	List<OptionType> getOptionTypes() {
		return [
			new OptionType(
				code: 'client-vm-report-cloud',
				name: 'Client Cloud',
				fieldName: 'cloudId',
				fieldContext: 'config',
				fieldLabel: 'Client Cloud',
				inputType: OptionType.InputType.SELECT,
				// ✅ built-in option source (commonly supported)
				optionSource: 'clouds',
				required: true,
				displayOrder: 0
			),
			new OptionType(
				code: 'client-vm-report-search',
				name: 'Search',
				fieldName: 'phrase',
				fieldContext: 'config',
				fieldLabel: 'VM Name starts with (optional)',
				required: false,
				displayOrder: 1
			)
		]
	}

	@Override
	HTMLResponse renderTemplate(ReportResult reportResult, Map<String, List<ReportResultRow>> reportRowsBySection) {
		ViewModel<Map> model = new ViewModel<Map>()
		model.object = [
			cloudName   : reportResult.configMap?.cloudName,
			rowsBySection: reportRowsBySection
		]
		getRenderer().renderTemplate("hbs/instanceReport", model)
	}

	@Override
	ContentSecurityPolicy getContentSecurityPolicy() {
		def csp = new ContentSecurityPolicy()
		csp.scriptSrc = '*.jsdelivr.net'
		csp.frameSrc = '*.digitalocean.com'
		csp.imgSrc = '*.wikimedia.org'
		csp.styleSrc = 'https: *.bootstrapcdn.com'
		csp
	}

	protected String toOnOff(def statusVal) {
		def s = (statusVal ?: "").toString().toLowerCase()
		if(s.contains("run") || s.contains("active") || s.contains("start")) return "ON"
		if(s.contains("stop") || s.contains("off") || s.contains("suspend")) return "OFF"
		return s ? s.toUpperCase() : "UNKNOWN"
	}
@Override
void process(ReportResult reportResult) {
	morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()

	Long displayOrder = 0L
	try {
		Long cloudId = reportResult.configMap?.cloudId?.toString()?.toLong()
		String phrase = reportResult.configMap?.phrase?.toString()?.trim()

		if(!cloudId) throw new RuntimeException("cloudId is missing/invalid")

		String cloudName = null
		List<GroovyRowResult> results = []

		withDbConnection { Connection dbConnection ->
			def sql = new Sql(dbConnection)

			// cloud name
			try {
				def row = sql.firstRow("SELECT name FROM compute_zone WHERE id = ?", [cloudId])
				cloudName = row?.name
			} catch(Throwable ignored) {
				cloudName = null
			}

			// instances (NOTE: if column names differ, we’ll catch error and print it)
			if(phrase) {
				String phraseMatch = phrase + "%"
				results = sql.rows("""
					SELECT id, name, status, max_cores, max_memory, max_storage
					FROM instance
					WHERE zone_id = ?
					  AND name LIKE ?
					ORDER BY name ASC
				""", [cloudId, phraseMatch])
			} else {
				results = sql.rows("""
					SELECT id, name, status, max_cores, max_memory, max_storage
					FROM instance
					WHERE zone_id = ?
					ORDER BY name ASC
				""", [cloudId])
			}
		}

		reportResult.configMap.cloudName = cloudName ?: "Cloud #${cloudId}"
		morpheus.async.report.saveReportResult(reportResult).blockingGet()

		Observable.fromIterable(results)
			.map { row ->
				Long memMb = (row.max_memory instanceof Number) ? ((Number)row.max_memory).longValue() : 0L
				Long memGb = memMb > 0 ? (long)Math.ceil(memMb / 1024.0d) : 0L

				Map<String, Object> data = [
					name   : row.name,
					cores  : row.max_cores ?: 0,
					ramGb  : memGb,
					storage: row.max_storage ?: 0,
					status : toOnOff(row.status)
				]

				new ReportResultRow(
					section: ReportResultRow.SECTION_MAIN,
					displayOrder: displayOrder++,
					dataMap: data
				)
			}
			.buffer(200)
			.doOnComplete {
				morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
			}
			.doOnError { Throwable t ->
				log.error("Report generation failed (Rx): ${t.message}", t)
				morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
			}
			.subscribe { rows ->
				morpheus.async.report.appendResultRows(reportResult, rows).blockingGet()
			}

	} catch(Throwable t) {
		log.error("Report generation failed: ${t.message}", t)

		// Write the error into report rows so you can see it in UI
		try {
			def errRow = new ReportResultRow(
				section: ReportResultRow.SECTION_MAIN,
				displayOrder: 0L,
				dataMap: [
					name   : "ERROR",
					cores  : 0,
					ramGb  : 0,
					storage: 0,
					status : t.message
				]
			)
			morpheus.async.report.appendResultRows(reportResult, [errRow]).blockingGet()
		} catch(Throwable ignored) {
			// ignore secondary failures
		}

		morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
	}
}
