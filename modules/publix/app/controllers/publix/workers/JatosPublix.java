package controllers.publix.workers;

import controllers.publix.IPublix;
import controllers.publix.JatosGroupChannel;
import controllers.publix.Publix;
import controllers.publix.StudyAssets;
import daos.common.ComponentResultDao;
import daos.common.StudyResultDao;
import exceptions.publix.*;
import general.common.Common;
import general.common.StudyLogger;
import models.common.*;
import models.common.workers.JatosWorker;
import play.Logger;
import play.Logger.ALogger;
import play.db.jpa.JPAApi;
import play.mvc.Controller;
import play.mvc.Result;
import services.publix.PublixErrorMessages;
import services.publix.PublixHelpers;
import services.publix.ResultCreator;
import services.publix.idcookie.IdCookieModel;
import services.publix.idcookie.IdCookieService;
import services.publix.workers.JatosErrorMessages;
import services.publix.workers.JatosPublixUtils;
import services.publix.workers.JatosStudyAuthorisation;
import utils.common.HttpUtils;
import utils.common.IOUtils;
import utils.common.JsonUtils;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Implementation of JATOS' public API for studies and components that are
 * started via JATOS' UI (run study or run component). A JATOS run is done by a
 * JatosWorker.
 * <p>
 * Between the UI and Publix a session variable is used to pass on the
 * information whether it is a study run or a component run. In case it is a
 * component run there is a second session variable which contains the component
 * ID.
 *
 * @author Kristian Lange
 */
@Singleton
public class JatosPublix extends Publix<JatosWorker> implements IPublix {

    private static final ALogger LOGGER = Logger.of(JatosPublix.class);

    /**
     * Parameter name that is used in a URL query string for an JATOS run.
     */
    public static final String JATOS_WORKER_ID = "jatosWorkerId";

    /**
     * Distinguish between study run and component run. In case of an component
     * run additionally distinguish between the start or whether it is already
     * finished.
     */
    public enum JatosRun {
        RUN_STUDY, // A full study run
        RUN_COMPONENT_START, // Single component run just started
        RUN_COMPONENT_FINISHED // Single component run in finished state
    }

    /**
     * Name of a key in the session. It stores what will be mapped to JatosRun.
     */
    public static final String SESSION_JATOS_RUN = "jatos_run";

    /**
     * Name of a key in the session. In case of a component JATOS run the
     * Component ID is stored in the session with this key.
     */
    public static final String SESSION_RUN_COMPONENT_ID = "run_component_id";

    /**
     * Name of a key in the session. It stores the username of the logged in JATOS user.
     */
    public static final String SESSION_USERNAME = "username";

    private final JatosPublixUtils publixUtils;
    private final JatosStudyAuthorisation studyAuthorisation;
    private final ResultCreator resultCreator;
    private final StudyLogger studyLogger;

    @Inject
    JatosPublix(JPAApi jpa, JatosPublixUtils publixUtils,
            JatosStudyAuthorisation studyAuthorisation,
            ResultCreator resultCreator, JatosGroupChannel groupChannel,
            IdCookieService idCookieService, JatosErrorMessages errorMessages,
            StudyAssets studyAssets, JsonUtils jsonUtils,
            ComponentResultDao componentResultDao,
            StudyResultDao studyResultDao, StudyLogger studyLogger, IOUtils ioUtils) {
        super(jpa, publixUtils, studyAuthorisation, groupChannel,
                idCookieService, errorMessages, studyAssets, jsonUtils,
                componentResultDao, studyResultDao, studyLogger, ioUtils);
        this.publixUtils = publixUtils;
        this.studyAuthorisation = studyAuthorisation;
        this.resultCreator = resultCreator;
        this.studyLogger = studyLogger;
    }

    @Override
    public Result startStudy(Long studyId, Long batchId) throws PublixException {
        LOGGER.info(".startStudy: studyId " + studyId + ", " + "batchId "
                + batchId + ", " + "logged-in username " + session(SESSION_USERNAME));
        Study study = publixUtils.retrieveStudy(studyId);
        Batch batch = publixUtils.retrieveBatchByIdOrDefault(batchId, study);
        JatosWorker worker = publixUtils.retrieveLoggedInUser().getWorker();
        studyAuthorisation.checkWorkerAllowedToStartStudy(worker, study, batch);
        LOGGER.info(".startStudy: study (study ID " + studyId + ", batch ID "
                + batchId + ") " + "assigned to worker with ID "
                + worker.getId());

        Long componentId = null;
        JatosRun jatosRun = publixUtils.retrieveJatosRunFromSession();
        Publix.session().remove(SESSION_JATOS_RUN);
        switch (jatosRun) {
            case RUN_STUDY:
                componentId = publixUtils.retrieveFirstActiveComponent(study).getId();
                break;
            case RUN_COMPONENT_START:
                componentId = Long.valueOf(session(SESSION_RUN_COMPONENT_ID));
                session().remove(SESSION_RUN_COMPONENT_ID);
                break;
            case RUN_COMPONENT_FINISHED:
                throw new ForbiddenPublixException(
                        JatosErrorMessages.STUDY_NEVER_STARTED_FROM_JATOS);
        }
        publixUtils.finishOldestStudyResult();
        StudyResult studyResult = resultCreator.createStudyResult(study, batch, worker);
        publixUtils.setUrlQueryParameter(studyResult);
        idCookieService.writeIdCookie(worker, batch, studyResult, jatosRun);
        studyLogger.log(study, "Started study run with " + JatosWorker.UI_WORKER_TYPE
                + " worker", batch, worker);
        return redirect(controllers.publix.routes.PublixInterceptor
                .startComponent(studyId, componentId, studyResult.getId(), null));
    }

    @Override
    public Result startComponent(Long studyId, Long componentId, Long studyResultId, String message)
            throws PublixException {
        LOGGER.info(".startComponent: studyId " + studyId + ", " + "componentId " + componentId + ", "
                + "studyResultId " + studyResultId + ", " + "logged-in username " + session(SESSION_USERNAME)
                + ", " + "message '" + message + "'");
        IdCookieModel idCookie = idCookieService.getIdCookie(studyResultId);
        Study study = publixUtils.retrieveStudy(studyId);
        Batch batch = publixUtils.retrieveBatch(idCookie.getBatchId());
        JatosWorker worker = publixUtils.retrieveTypedWorker(idCookie.getWorkerId());
        Component component = publixUtils.retrieveComponent(study, componentId);
        studyAuthorisation.checkWorkerAllowedToDoStudy(worker, study, batch);
        publixUtils.checkComponentBelongsToStudy(study, component);

        // Check if it's a single component show or a whole study show
        StudyResult studyResult = publixUtils.retrieveStudyResult(worker, study, studyResultId);
        JatosRun jatosRun = idCookie.getJatosRun();
        switch (jatosRun) {
            case RUN_STUDY:
                break;
            case RUN_COMPONENT_START:
                jatosRun = JatosRun.RUN_COMPONENT_FINISHED;
                break;
            case RUN_COMPONENT_FINISHED:
                ComponentResult lastComponentResult = studyResult.getLastComponentResult()
                        .orElseThrow(() -> new InternalServerErrorPublixException(
                                "Couldn't find last run component.")
                        );
                if (!lastComponentResult.getComponent().equals(component)) {
                    // It's already the second component (first is finished and it
                    // isn't a reload of the same one). Finish study after first component.
                    return redirect(controllers.publix.routes.PublixInterceptor
                            .finishStudy(studyId, studyResult.getId(), true, null));
                }
                break;
        }

        ComponentResult componentResult;
        try {
            componentResult = publixUtils.startComponent(component, studyResult, message);
        } catch (ForbiddenReloadException | ForbiddenNonLinearFlowException e) {
            return redirect(controllers.publix.routes.PublixInterceptor
                    .finishStudy(studyId, studyResult.getId(), false, e.getMessage()));
        }
        idCookieService.writeIdCookie(worker, batch, studyResult, componentResult, jatosRun);
        return studyAssets
                .retrieveComponentHtmlFile(study.getDirName(), component.getHtmlFilePath())
                .asJava();
    }

    @Override
    public Result abortStudy(Long studyId, Long studyResultId, String message)
            throws PublixException {
        LOGGER.info(".abortStudy: studyId " + studyId + ", " + "studyResultId " + studyResultId + ", "
                + "logged-in username " + session(SESSION_USERNAME) + ", " + "message \"" + message + "\"");
        IdCookieModel idCookie = idCookieService.getIdCookie(studyResultId);
        Study study = publixUtils.retrieveStudy(studyId);
        Batch batch = publixUtils.retrieveBatch(idCookie.getBatchId());
        JatosWorker worker = publixUtils.retrieveTypedWorker(idCookie.getWorkerId());
        studyAuthorisation.checkWorkerAllowedToDoStudy(worker, study, batch);

        StudyResult studyResult = publixUtils.retrieveStudyResult(worker, study, studyResultId);
        if (!PublixHelpers.studyDone(studyResult)) {
            publixUtils.abortStudy(message, studyResult);
            groupChannel.closeGroupChannelAndLeaveGroup(studyResult);
        }
        idCookieService.discardIdCookie(studyResult.getId());
        studyLogger.log(study, "Aborted study run", worker);

        if (HttpUtils.isAjax()) {
            return ok(" "); // jQuery.ajax cannot handle empty responses
        } else {
            if (message != null) {
                Controller.flash("info", PublixErrorMessages.studyFinishedWithMessage(message));
            }
            return redirect(Common.getPlayHttpContext() + "jatos/" + study.getId());
        }
    }

    @Override
    public Result finishStudy(Long studyId, Long studyResultId, Boolean successful, String message)
            throws PublixException {
        LOGGER.info(".finishStudy: studyId " + studyId + ", " + "studyResultId " + studyResultId + ", "
                + "logged-in username " + session(SESSION_USERNAME) + ", " + "successful " + successful + ", "
                + "message \"" + message + "\"");
        IdCookieModel idCookie = idCookieService.getIdCookie(studyResultId);
        Study study = publixUtils.retrieveStudy(studyId);
        Batch batch = publixUtils.retrieveBatch(idCookie.getBatchId());
        JatosWorker worker = publixUtils.retrieveTypedWorker(idCookie.getWorkerId());
        studyAuthorisation.checkWorkerAllowedToDoStudy(worker, study, batch);

        StudyResult studyResult = publixUtils.retrieveStudyResult(worker, study, studyResultId);
        if (!PublixHelpers.studyDone(studyResult)) {
            publixUtils.finishStudyResult(successful, message, studyResult);
            groupChannel.closeGroupChannelAndLeaveGroup(studyResult);
        }
        idCookieService.discardIdCookie(studyResult.getId());
        studyLogger.log(study, "Finished study run", worker);

        if (HttpUtils.isAjax()) {
            return ok(" "); // jQuery.ajax cannot handle empty responses
        } else {
            if (message != null) {
                Controller.flash("info", PublixErrorMessages.studyFinishedWithMessage(message));
            }
            return redirect(Common.getPlayHttpContext() + "jatos/" + study.getId());
        }
    }

}
