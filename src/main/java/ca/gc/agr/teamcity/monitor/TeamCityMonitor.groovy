package ca.gc.agr.teamcity.monitor

import groovy.util.slurpersupport.NodeChild
import groovyx.net.http.HttpResponseDecorator
import groovyx.net.http.RESTClient

import java.text.SimpleDateFormat
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory

/**
 * Monitors TeamCity for any failed builds, and turns on the Siren if a failed build 
 * is detected. Builds where the responsibility has been taken are not considered failed.
 *
 * @author Val Blant
 */
class TeamCityMonitor implements Runnable {
	private static final Log log = LogFactory.getLog(TeamCityMonitor.class);
	
	private static final String TC_HOST = 'http://mackay.agr.gc.ca';
	private static final String CONTEXT_ROOT = '/TCAM';
	private static final String TC_REST_API_BUILDS = '/httpAuth/app/rest/builds/';
	
	private static final String SIREN_IP = '10.113.164.158';
	private static final int SIREN_PORT = 8080;
	
	/**
	 * Update interval
	 */
	private static final int UPDATE_EVERY = 10; // Seconds
	
	/**
	 * Prevent siren operation after hours
	 */
	private static final boolean SUPRESS_SIREN_AFTER_HOURS = true;
	
	
	
	private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
	
	public static void main(args) {
		log.info(" ============ Startup Parameters ============ ");
		log.info("   TeamCity URL: ${TC_HOST}${CONTEXT_ROOT}");
		log.info("   Siren Address: ${SIREN_IP}:${SIREN_PORT}");
		log.info("   Refresh state: every ${UPDATE_EVERY} seconds.");
		log.info("   Supress Siren after hours: ${SUPRESS_SIREN_AFTER_HOURS}");
		log.info(" ============================================ ");
		
		scheduler.scheduleAtFixedRate( new TeamCityMonitor(), 0, UPDATE_EVERY, TimeUnit.SECONDS );
	}
	
	@Override
	public void run() {
		boolean redAlert = false;
		
		if ( !SUPRESS_SIREN_AFTER_HOURS || !isAfterHours() ) {
			// Get all failed builds after the last successful one. That just means get all failed
			// build configurations
			//
			NodeChild buildsElement = queryTeamCity(TC_REST_API_BUILDS, 'locator=sinceBuild:(status:success)');
				
			if ( buildsElement != null ) {
				int numberOfFailedBuilds = buildsElement.children().size();
				if ( numberOfFailedBuilds > 0 ) {
					// Collect the URIs for broken builds
					List<String> brokenBuildUris = buildsElement.children().collect { it.@href.toString() }
					
					// Check if the build was taken responsibility for
					brokenBuildUris = brokenBuildUris.findAll { !isResponsibilityTaken(it) };
	
					// See if there are any failed builds remaining (no responsibility taken)
					if ( brokenBuildUris.size() > 0 ) {
						log.info("Detected ${brokenBuildUris.size()} failed builds that no one took responsibility for! Red Alert!");
						redAlert = true;
					}
				}
			}
			else {
				throw new IllegalStateException("TeamCity returned no XML data");
			}
		}
		
		redAlert ? sirenOn() : sirenOff();
	}

	
	/**
	 * @return true between 6pm and 6am, and all day on Saturday and Sunday
	 */
	private boolean isAfterHours() {
		Date now = new Date();
		
		String hour = new SimpleDateFormat("HH").format(now);
		String day = new SimpleDateFormat("E").format(now);
		
		boolean supress = (day == "Sat" || day == "Sun" || hour.toInteger() > 17 || hour.toInteger() < 6);
		
		if ( supress ) log.info("Supressing Siren operation, b/c it is after hours.");
		
		return supress;
	}
	

	/**
	 * Turn off the siren
	 */
	private void sirenOff() {
		log.info("There are no failed builds. Siren OFF.");
		sendSirenCommand("SIREN_OFF");
	} 

	/**
	 * Turn on the siren
	 */
	private void sirenOn() {
		log.info("Builds are failing. Siren ON.");
		sendSirenCommand("SIREN_ON");
	}
	
	/**
	 * Send command to siren.
	 * 
	 * @param command
	 */
	private void sendSirenCommand(String command) {
		Socket s = new Socket(SIREN_IP, SIREN_PORT);
		String response;
		
		s.withStreams { input, output ->
			output << command + "\n";
			response = input.newReader().readLine();
		}
		
		if ( response != "OK" ) {
			throw new RuntimeException("The Siren is not working correctly!");
		}
	}

	/**
	 * @param buildUri The URI of the build we want to check
	 * @return true if the responsibility for this build was taken. False otherwise.
	 */
	private boolean isResponsibilityTaken(String buildUri) {
		boolean responsibilityTaken = false;
		
		NodeChild failedBuildInfo = queryTeamCity(buildUri);
		if ( failedBuildInfo != null ) {

			// Get build name
			String buildName = failedBuildInfo.buildType.@name;
			String logString = "Broken build: ${buildName}";
			
			// Get the name of the last committer
			def triggeredElement = failedBuildInfo.triggered;
			if ( triggeredElement != null && triggeredElement.@type == 'user' ) {
				logString += " (broken by ${triggeredElement.user.@name})"
			}

			// Get the uri for BuildType info. That's where investigations are tracked
			String buildTypeHref = failedBuildInfo.buildType.@href;
			NodeChild buildTypeInfo = queryTeamCity(buildTypeHref);
			if ( buildTypeInfo != null ) {
				def investigations = buildTypeInfo.investigations;
				if ( investigations != null ) {
					// Get the investigation info
					NodeChild investigationsInfo = queryTeamCity(investigations.@href.toString());
					
					if ( investigationsInfo != null ) {
						String state = investigationsInfo.investigation.@state;
						
						responsibilityTaken = (state == "TAKEN" || state == "FIXED"); 
					}
					
					if ( responsibilityTaken ) {
						logString += ", responsibility taken by ${investigationsInfo.investigation.assignment.user.@name}"
					}
				}
			}
			
			log.info(logString);
		}
		else {
			throw new IllegalStateException("TeamCity returned no data for build ${buildUri}");
		}
		
		return responsibilityTaken;
	}
	
	/**
	 * Queries TeamCity through the REST API
	 * 
	 * @param restPath The REST API path
	 * @param queryString The query string (stuff after the '?' in the URL)
	 * @return Parsed XML Node
	 */
	private NodeChild queryTeamCity(String restPath, String queryString = null) {
		if ( restPath.contains('?') ) {
			// Query parameters were specified as part of the path, so we need to split them out
			//
			def parts = restPath.split('\\?');
			restPath = parts[0]; 
			queryString = parts[1];
		}
		
		RESTClient client = new RESTClient(TC_HOST);
		client.headers['Authorization'] = 'Basic '+ "blantv:qq1rgh".getBytes().encodeBase64()
		HttpResponseDecorator response = client.get(
			path: CONTEXT_ROOT + restPath,
			queryString: queryString );
		
		if ( response.isSuccess() ) {
			return response.getData();
		}
		else {
			throw new IllegalStateException("TeamCity responded with error code: ${response.getStatus()}");
		}

	}

}
