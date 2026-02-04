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
import groovy.sql.Sql
import groovy.util.logging.Slf4j

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
			cloudName    : reportResult.configMap?.cloudName,
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

	void process(ReportResult reportResult) {
  morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.generating).blockingAwait()

  Long displayOrder = 0L
  Long total = 0L
  Long rowErrors = 0L

  def cloudId = reportResult?.configMap?.cloudId ?: reportResult?.config?.cloudId
  def phrase = (reportResult?.configMap?.phrase ?: reportResult?.config?.phrase ?: "").toString().trim().toLowerCase()

  try {
    DataQuery q = new DataQuery()
    if(cloudId) {
      // common filter name is cloud.id in Morpheus queries
      q.withFilter("cloud.id", cloudId)
    }

    morpheus.async.computeServer.list(q)
      .buffer(50)
      .flatMap { servers ->
        def rows = []
        servers.each { server ->
          total++
          try {
            def name = (server?.name ?: server?.externalId ?: "unknown").toString()

            // optional search: "starts with"
            if(phrase && !name.toLowerCase().startsWith(phrase)) {
              return
            }

            Map<String, Object> data = [:]
            Long cpuCores = (server?.maxCpu ?: 1L) as Long
            Double ramGB  = ((server?.maxMemory ?: 0L) / (1024d * 1024d * 1024d))
            Double diskGB = ((server?.maxStorage ?: 0L) / (1024d * 1024d * 1024d))

            data.name = name
            data.cpuCores = cpuCores
            data.ramGB = ramGB
            data.diskGB = diskGB
            data.powerState = server?.powerState?.toString() ?: "unknown"
            data.status = server?.status ?: "unknown"
            data.cloud = server?.cloud?.name ?: "unknown"
            data.owner = server?.owner?.username ?: "unknown"

            rows << new ReportResultRow(
              reportResultId: reportResult.id,
              displayOrder: displayOrder++,
              dataMap: data
            )
          } catch (Throwable t) {
            rowErrors++
            log.warn("Row build failed for server id=${server?.id} name=${server?.name}: ${t.message}", t)
          }
        }

        if(rows) {
          return morpheus.async.report.appendResultRows(reportResult, rows).toObservable()
        } else {
          return rx.Observable.empty()
        }
      }
      .doOnComplete {
        log.info("Report done. cloudId=${cloudId} phrase='${phrase}' totalServers=${total}, rowErrors=${rowErrors}")
        morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.ready).blockingAwait()
      }
      .doOnError { Throwable t ->
        log.error("Error generating report cloudId=${cloudId} phrase='${phrase}' totalServers=${total}, rowErrors=${rowErrors}", t)
        morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
      }
      .subscribe()

  } catch (Throwable t) {
    log.error("Fatal error before stream started", t)
    morpheus.async.report.updateReportResultStatus(reportResult, ReportResult.Status.failed).blockingAwait()
  }
}
}