package controllers.gui;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import models.StudyModel;
import models.UserModel;
import models.workers.Worker;
import persistance.IStudyDao;
import persistance.workers.IWorkerDao;
import play.Logger;
import play.db.jpa.Transactional;
import play.mvc.Controller;
import play.mvc.Http;
import play.mvc.Result;
import play.mvc.With;
import services.gui.Breadcrumbs;
import services.gui.JatosGuiExceptionThrower;
import services.gui.MessagesStrings;
import services.gui.StudyService;
import services.gui.UserService;
import services.gui.WorkerService;
import utils.JsonUtils;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import exceptions.gui.JatosGuiException;

/**
 * Controller that handles all worker actions in JATOS' GUI
 * 
 * @author Kristian Lange
 */
@With(JatosGuiAction.class)
@Singleton
public class Workers extends Controller {

	private static final String CLASS_NAME = Workers.class.getSimpleName();

	private final JatosGuiExceptionThrower jatosGuiExceptionThrower;
	private final StudyService studyService;
	private final UserService userService;
	private final WorkerService workerService;
	private final JsonUtils jsonUtils;
	private final IStudyDao studyDao;
	private final IWorkerDao workerDao;

	@Inject
	Workers(JatosGuiExceptionThrower jatosGuiExceptionThrower,
			StudyService studyService, UserService userService,
			WorkerService workerService, IStudyDao studyDao,
			JsonUtils jsonUtils, IWorkerDao workerDao) {
		this.jatosGuiExceptionThrower = jatosGuiExceptionThrower;
		this.studyService = studyService;
		this.userService = userService;
		this.workerService = workerService;
		this.studyDao = studyDao;
		this.jsonUtils = jsonUtils;
		this.workerDao = workerDao;
	}

	/**
	 * Shows view with worker details.
	 */
	@Transactional
	public Result index(Long workerId, int httpStatus) throws JatosGuiException {
		Logger.info(CLASS_NAME + ".index: " + "workerId " + workerId + ", "
				+ "logged-in user's email " + session(Users.SESSION_EMAIL));
		UserModel loggedInUser = userService.retrieveLoggedInUser();
		List<StudyModel> studyList = studyDao.findAllByUser(loggedInUser
				.getEmail());
		Worker worker = workerDao.findById(workerId);
		workerService.checkWorker(worker, workerId);

		Breadcrumbs breadcrumbs = Breadcrumbs.generateForWorker(worker,
				Breadcrumbs.RESULTS);
		return status(httpStatus,
				views.html.gui.result.workersStudyResults.render(studyList,
						loggedInUser, breadcrumbs, worker));
	}

	@Transactional
	public Result index(Long workerId) throws JatosGuiException {
		return index(workerId, Http.Status.OK);
	}

	/**
	 * Ajax request
	 * 
	 * Remove a worker including its results.
	 */
	@Transactional
	public Result remove(Long workerId) throws JatosGuiException {
		Logger.info(CLASS_NAME + ".remove: workerId " + workerId + ", "
				+ "logged-in user's email " + session(Users.SESSION_EMAIL));
		Worker worker = workerDao.findById(workerId);
		UserModel loggedInUser = userService.retrieveLoggedInUser();
		workerService.checkWorker(worker, workerId);

		workerService.checkRemovalAllowed(worker, loggedInUser);
		workerDao.remove(worker);
		return ok();
	}

	/**
	 * Ajax request
	 * 
	 * Returns a list of workers (as JSON) that did the specified study.
	 */
	@Transactional
	public Result tableDataByStudy(Long studyId) throws JatosGuiException {
		Logger.info(CLASS_NAME + ".tableDataByStudy: studyId " + studyId + ", "
				+ "logged-in user's email " + session(Users.SESSION_EMAIL));
		StudyModel study = studyDao.findById(studyId);
		UserModel loggedInUser = userService.retrieveLoggedInUser();
		studyService.checkStandardForStudy(study, studyId, loggedInUser);

		String dataAsJson = null;
		try {
			Set<Worker> workerSet = workerService.retrieveWorkers(study);
			dataAsJson = jsonUtils.allWorkersForUI(workerSet);
		} catch (IOException e) {
			String errorMsg = MessagesStrings.PROBLEM_GENERATING_JSON_DATA;
			jatosGuiExceptionThrower.throwAjax(errorMsg,
					Http.Status.INTERNAL_SERVER_ERROR);
		}
		return ok(dataAsJson);
	}

}