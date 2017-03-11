package com.italtel.chatbot.codemotion.logic.service;

import java.util.Collection;
import java.util.List;

import javax.annotation.Resource;
import javax.ejb.Stateless;
import javax.ejb.Timeout;
import javax.ejb.Timer;
import javax.ejb.TimerService;
import javax.inject.Inject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import com.italtel.chatbot.codemotion.logic.dto.TextDTO;
import com.italtel.chatbot.codemotion.logic.entities.Question;
import com.italtel.chatbot.codemotion.logic.entities.User;
import com.italtel.chatbot.codemotion.logic.entities.UserAnswer;
import com.italtel.chatbot.codemotion.logic.enums.AnswerLabel;
import com.italtel.chatbot.codemotion.logic.utils.MessageUtils;

@Stateless
public class GameServiceBean {

	@Resource
	TimerService timerService;

	@Inject
	private UserServiceBean userBean;

	@Inject
	private QuestionServiceBean questionBean;

	@Inject
	private ConfigServiceBean configBean;

	public String startGame(String userId, String username, String email) {
		String responseText = null;
		User user = userBean.findUser(userId);
		if (user == null) {
			user = userBean.addNewUser(userId, email, username);
		}
		if ("FINISHED".equals(user.getStatus())) {
			Question nextQuestion = questionBean.getNextQuestion(userId);
			if (nextQuestion != null) {
				user.setStatus("WAITING");
				user.setLastQuestionId(nextQuestion.getId());
				user.setStageId(nextQuestion.getId());
				if (nextQuestion != null) {
					responseText = MessageUtils.buildMessage(nextQuestion.getText(), nextQuestion.getAns1(),
							nextQuestion.getAns2(), nextQuestion.getAns3(), nextQuestion.getAns4());
					startTimer(userId);
				}
			} else {
				responseText = "You have already completed the game!<br>Come to **Cisco** Lab to see if you are the geek-est of Codemotion!<br>Best of luck!";
			}
		} else if ("WAITING".equals(user.getStatus())) {
			responseText = "The game has already started! The question is:<br>";
			Integer lastQuestionId = user.getLastQuestionId();
			Question lastQuestion = questionBean.findQuestion(lastQuestionId);
			responseText = responseText + MessageUtils.buildMessage(lastQuestion.getText(), lastQuestion.getAns1(),
					lastQuestion.getAns2(), lastQuestion.getAns3(), lastQuestion.getAns4()) + "<br>Hurry!";
		}
		return responseText;
	}

	public String processAnswer(User user, String text) {
		String responseText = null;
		if (user != null) {
			String userId = user.getId();
			// Check status
			if ("WAITING".equals(user.getStatus())) {
				Integer lastQuestionId = user.getLastQuestionId();
				if (lastQuestionId != null) {
					// Stop timer
					Timer timer = getTimerByUserId(userId);
					long timeRemaining = timer.getTimeRemaining();
					timer.cancel();
					String config = configBean.getConfig("QUESTION_TIMEOUT");
					long timeout = Long.valueOf(config);
					Question lastQuestion = questionBean.findQuestion(lastQuestionId);
					Integer correctAns = lastQuestion.getCorrectAns();
					AnswerLabel correctLabel = AnswerLabel.getByValue(correctAns);
					AnswerLabel ansLabel = AnswerLabel.getByLabel(text);
					UserAnswer ua = questionBean.findAnswerByUserIdAndQuestionId(userId, lastQuestionId);
					boolean correct = correctLabel != null && correctLabel.equals(ansLabel);
					Integer score = 0;
					if (correct) {
						// Correct answer
						// Convert times in seconds, rounded to 1st decimal
						// e.g. 4569 -> 4.6
						double timeRemainingRounded = Math.round(timeRemaining / 100.0) / 10.0;
						double timeoutRounded = Math.round(timeout / 100.0) / 10.0;
						// Compute score
						score = 100 + (int) (timeRemainingRounded * 10);
						// Pick a message
						String msg = MessageUtils.pickCorrectMsg();
						responseText = msg.concat("<br>You answered in ")
								.concat(String.valueOf(timeoutRounded - timeRemainingRounded))
								.concat(" seconds. <br>You earned **").concat(score.toString()).concat("** points!");
					} else {
						// Wrong answer
						score = 0;
						// Pick a message
						String msg = MessageUtils.pickWrongMsg();
						responseText = msg.concat("<br>The correct answer was: **").concat(correctLabel.name())
								.concat("**");
					}
					ua.setCorrect(correct);
					Integer ansGiven = null;
					if (ansLabel != null) {
						ansGiven = ansLabel.getValue();
					}
					ua.setAnsGiven(ansGiven);
					ua.setAnswered(true);
					ua.setScore(score);
					Integer totalScore = score;
					if (user.getTotalScore() != null) {
						totalScore += user.getTotalScore();
					}
					user.setTotalScore(totalScore);
					user.setStatus("FINISHED");
					if (questionBean.getNextQuestion(userId) != null) {
						responseText = responseText.concat("<br>Type **next** to continue.");
					} else {
						responseText = responseText.concat("<br><br>You have completed the game!");
						responseText = responseText
								.concat("<br><br>Give me some seconds to produce your report...<br><br>");
					}
				}
			} else {
				responseText = "You have already completed the game!<br>Come to **Cisco** Lab to see if you are the geek-est of Codemotion!<br>Best of luck!";
			}
		} else {
			responseText = "Type **play** to start the game!";
		}
		return responseText;
	}

	public String getReport(User user) {
		String responseText = null;
		if (user != null && "FINISHED".equals(user.getStatus()) && questionBean.getNextQuestion(user.getId()) == null) {
			// The game is complete, show report
			responseText = "Here are your results:<br><br>";
			List<UserAnswer> answers = questionBean.findAllUserAnswers(user.getId());
			for (UserAnswer answer : answers) {
				Integer qId = answer.getId().getQuestionId();
				Question q = questionBean.findQuestion(qId);
				String report = MessageUtils.buildReport(q.getText(), q.getAns1(), q.getAns2(), q.getAns3(),
						q.getAns4(), q.getCorrectAns(), answer.getAnsGiven(), answer.getScore());
				responseText = responseText.concat(report);
			}
			responseText = responseText.concat("**YOUR TOTAL SCORE IS: ").concat(user.getTotalScore().toString())
					.concat(" POINTS**<br>")
					.concat("Come to **Cisco** Lab to see if you are the geek-est of Codemotion!<br>");
		}
		return responseText;
	}

	public String getScore(String userId) {
		// Tell total score
		String responseText = null;
		User user = userBean.findUser(userId);
		Integer score = 0;
		if (user != null && user.getTotalScore() != null) {
			score = user.getTotalScore();
		}
		responseText = "Your score is: **".concat(String.valueOf(score)).concat("** points");
		return responseText;
	}

	public String getHelp() {
		String config = configBean.getConfig("QUESTION_TIMEOUT");
		Long timeoutInMillis = Long.valueOf(config);
		int timeoutInSeconds = (int) (timeoutInMillis / 1000);
		return "Here are the things you can ask me: <br><ul><li>**play**: start the game!</li>"
				+ "<li>**a**, **b**, **c** or **d**: answer - please don't write the full text of the answer or I will get confused!</li>"
				+ "<li>**next**: go to the next question</li>" + "<li>**score**: view your current score</li>"
				+ "<li>**help**: view this help again!</li>" + "</ul> " + "Beware: you have just " + timeoutInSeconds
				+ " seconds to answer! The faster you are the more points you obtain!";
	}

	public boolean isGameComplete(User user) {
		boolean complete = false;
		if (user != null) {
			Question nextQuestion = questionBean.getNextQuestion(user.getId());
			if (nextQuestion == null) {
				// Game complete
				complete = true;
			}
		}
		return complete;
	}

	public void sendResponse(TextDTO request, String responseText) {
		System.out.println(responseText);
		String sparkHost = configBean.getConfig("SPARK_HOST");
		String sparkPort = configBean.getConfig("SPARK_PORT");
		String sparkCR = configBean.getConfig("SPARK_CONTEXT_ROOT");
		String baseURI = "http://".concat(sparkHost).concat(":").concat(sparkPort).concat(sparkCR);
		Client client = ClientBuilder.newClient();
		WebTarget target = client.target(baseURI + "/api/spark/messages/write");
		TextDTO newResponse = new TextDTO(responseText);
		newResponse.setConversationId(request.getConversationId());
		newResponse.setTargetUsername(request.getEmail());
		Entity<TextDTO> entity = Entity.entity(newResponse, MediaType.APPLICATION_JSON);
		Response response = target.request().post(entity);
		response.close(); // You should close connections!
	}

	public void startTimer(String userId) {
		long duration = 10000;
		System.out.println("Timer started");
		String config = configBean.getConfig("QUESTION_TIMEOUT");
		if (config != null) {
			try {
				duration = Long.valueOf(config);
			} catch (NumberFormatException e) {
				e.printStackTrace();
			}
		}
		timerService.createTimer(duration, userId);
	}

	@Timeout
	public void timeout(Timer timer) {
		System.out.println("CodemotionAPI: timeout occurred");
		// timer.cancel();
		String userId = (String) timer.getInfo();
		User user = userBean.findUser(userId);
		if (user != null) {
			user.setStatus("FINISHED");
			Integer lastQuestionId = user.getLastQuestionId();
			UserAnswer ua = questionBean.findAnswerByUserIdAndQuestionId(userId, lastQuestionId);
			ua.setAnswered(true);
			ua.setCorrect(false);
			ua.setScore(0);
			Question lastQuestion = questionBean.findQuestion(lastQuestionId);
			Integer correctAns = lastQuestion.getCorrectAns();
			AnswerLabel correctLabel = AnswerLabel.getByValue(correctAns);
			String msg = "<br>Time out! You didn't answer on time!";
			String responseText = msg.concat("<br>The correct answer was: **").concat(correctLabel.name()).concat("**");
			if (questionBean.getNextQuestion(userId) != null) {
				responseText = responseText.concat("<br>Type **next** to continue.");
			} else {
				responseText = responseText.concat("<br><br>You have completed the game!");
				responseText = responseText.concat("<br><br>Give me some seconds to produce your report...<br><br>");
			}
			TextDTO textDTO = new TextDTO(responseText);
			textDTO.setEmail(user.getEmail());
			sendResponse(textDTO, responseText);
			String report = getReport(user);
			if (report != null) {
				// Send report
				try {
					Thread.sleep(5000);
				} catch (InterruptedException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				sendResponse(textDTO, report);
			}
		}
	}

	public Timer getTimerByUserId(String userId) {
		Timer result = null;
		Collection<Timer> timers = timerService.getTimers();
		for (Timer timer : timers) {
			if (userId.equals(timer.getInfo())) {
				result = timer;
			}
		}
		return result;
	}
}
