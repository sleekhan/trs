package net.ryan.trs

import org.joda.time._
import org.springframework.boot.{SpringApplication}
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.context.annotation.{Bean, Configuration}
import org.springframework.data.annotation.Id
import org.springframework.data.mongodb.core.index.Indexed
import org.springframework.data.mongodb.core.mapping.Document
import org.springframework.data.mongodb.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import org.springframework.http.{HttpStatus, MediaType}
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.server.{ServerRequest, ServerResponse}
import reactor.core.publisher.{Flux, Mono}
import org.springframework.web.reactive.function.server.RouterFunctions._
import org.springframework.web.reactive.function.BodyInserters.fromObject
import org.springframework.web.reactive.function.server.RequestPredicates._


import scala.beans.BeanProperty


@SpringBootApplication
class TRSApplication {}


object TRSApplication extends App {
	SpringApplication.run(classOf[TRSApplication], args: _*)
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Time Record Services
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

@Configuration
class TRRouteConfiguration(trService: TRService) {

	@Bean
	def routesTr() =
		route(POST("/trservice/v1/tr/{idcard}"), req => trService.createOrUpdateTimeRecord(req))
			.andRoute(POST("/trservice/v1/tr"), req => trService.getTimeRecords(req))
}

@Service
class TRService(trRepository: TRRepository, userRepository: UserRepository) {

	import mail._

	val endDay = stringToDateTime("9999-09-09")

	def cropTime(date: DateTime): DateTime = {
		date.withHourOfDay(9).withMinuteOfHour(0).withSecondOfMinute(0).withMillisOfSecond(0)
	}

	def stringToDateTime(dateTime: String): DateTime = DateTime.parse(dateTime).withHourOfDay(9).withMinuteOfHour(0).withSecondOfMinute(0)


	def createOrUpdateTimeRecord(request: ServerRequest): Mono[ServerResponse] = {

		val idcard = request.pathVariable("idcard")
		val toDay = cropTime(DateTime.now())


		userRepository.findByIdCard(idcard)
			.flatMap(
				u => trRepository.findByIdAndDate(u.idcard, toDay)
					.flatMap(t1 => {
						trRepository.save(TimeRecord(t1.id, u.email, t1.idcard, t1.date, t1.in, DateTime.now()))
							.flatMap(t2 => ServerResponse.ok().build)
					})
					.switchIfEmpty(
						trRepository.save(TimeRecord(null, u.email, u.idcard, toDay, DateTime.now(), endDay))
							.flatMap(t3 => ServerResponse.ok().build)
					)

			).switchIfEmpty(ServerResponse.status(HttpStatus.NO_CONTENT).build())
	}

	def getTimeRecords(request: ServerRequest): Mono[ServerResponse] = {

		val trQuery = request.bodyToMono(classOf[TRSearchDTO])
		trQuery.flatMap(q => ServerResponse.ok.body(
			trRepository.findByEmailAndDateDuration(q.email, stringToDateTime(q.startDate), stringToDateTime(q.endDate))
				.map[TimeRecordDTO](tr =>
				TimeRecordDTO(tr.email, tr.date.toString("yyyy-MM-dd"), tr.in.toString("yyyy-MM-dd HH:mm"),
					if (tr.out != endDay) tr.out.toString("yyyy-MM-dd HH:mm") else "",
					if (tr.out != endDay) {

						val gap = (tr.out.getMillis - tr.in.getMillis) / 3600000d
						if (gap < 8) "%.1f".format(gap - 0.5) else "%.1f".format(gap - 1)
					} else {
						""
					}
				))
			, classOf[TimeRecordDTO]).switchIfEmpty(ServerResponse.status(HttpStatus.NO_CONTENT).build()))

	}
}

@Document
case class TRSearchDTO(@BeanProperty email: String,
											 @BeanProperty startDate: String,
											 @BeanProperty endDate: String
											)

@Document
case class TimeRecordDTO(@BeanProperty email: String,
												 @BeanProperty date: String,
												 @BeanProperty in: String,
												 @BeanProperty out: String,
												 @BeanProperty timeDiff: String
												)

@Document
case class TimeRecord(@Id id: String,
											@BeanProperty @Indexed email: String,
											@BeanProperty @Indexed idcard: String,
											@BeanProperty date: DateTime,
											@BeanProperty in: DateTime,
											@BeanProperty out: DateTime)

trait TRRepository extends ReactiveCrudRepository[TimeRecord, String] {

	@Query("{'email': ?0, 'date': ?1}")
	def findByEmailAndDate(email: String, currentDate: DateTime): Mono[TimeRecord]

	@Query("{'idcard': ?0, 'date': ?1}")
	def findByIdAndDate(idcard: String, currentDate: DateTime): Mono[TimeRecord]

	@Query("{'email': ?0, 'date': {$gte: ?1, $lte: ?2}}.sort({ 'date': -1})")
	def findByEmailAndDateDuration(email: String, startDate: DateTime, endDate: DateTime): Flux[TimeRecord]

}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// User Services
///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

@Configuration
class UserRouteConfiguration(userService: UserService) {

	@Bean
	def routesUser() =
		route(GET("/userservice/v1/users"), _ => userService.getAllUsers())
			.andRoute(GET("/userservice/v1/user"), req => userService.getUserByEmail(req.queryParam("email").get()))
			.andRoute(POST("/userservice/v1/user"), req => userService.createOrUpdateUser(req))
}

@Service
class UserService(userRepository: UserRepository) {
	def getAllUsers(): Mono[ServerResponse] = ServerResponse.ok().body(userRepository.findAll(), classOf[User])

	def getUserByEmail(email: String): Mono[ServerResponse] = {
		userRepository.findByEmail(email)
			.flatMap(user => ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).body(fromObject(user)))
			.switchIfEmpty {
				ServerResponse.status(HttpStatus.NO_CONTENT).build()
			}
	}

	def createOrUpdateUser(request: ServerRequest): Mono[ServerResponse] = {
		val user: Mono[User] = request.bodyToMono(classOf[User])
		userRepository.saveUser(user).flatMap(value => ServerResponse.ok().body(fromObject(value)))
			.switchIfEmpty(ServerResponse.status(HttpStatus.INTERNAL_SERVER_ERROR).build())
	}
}


@Document
case class User(@Id id: String,
								@BeanProperty @Indexed(unique = true) email: String,
								@BeanProperty @Indexed(unique = true) idcard: String,
								@BeanProperty name: String)

trait UserRepository extends ReactiveCrudRepository[User, String] {

	@Query("{'email': ?0}")
	def findByEmail(email: String): Mono[User]

	@Query("{'idcard': ?0}")
	def findByIdCard(email: String): Mono[User]


	def saveUser(user: Mono[User]): Mono[User] = user.flatMap(newUser =>
		findByEmail(newUser.email).defaultIfEmpty(newUser).map(existUser =>
			if (existUser.id != null) User(existUser.id, newUser.email, newUser.idcard, newUser.name) else newUser
		)).flatMap(u => save(u.asInstanceOf[User]))
}


//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
// Mail
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

package object mail {

	implicit def stringToSeq(single: String): Seq[String] = Seq(single)

	implicit def liftToOption[T](t: T): Option[T] = Some(t)

	sealed abstract class MailType

	case object Plain extends MailType

	case object Rich extends MailType

	case object MultiPart extends MailType

	case class Mail(
									 from: (String, String), // (email -> name)
									 to: Seq[String],
									 cc: Seq[String] = Seq.empty,
									 bcc: Seq[String] = Seq.empty,
									 subject: String,
									 message: String,
									 richMessage: Option[String] = None,
									 attachment: Option[(java.io.File)] = None
								 )

	object send {
		def a(mail: Mail) {
			import org.apache.commons.mail._

			val format =
				if (mail.attachment.isDefined) MultiPart
				else if (mail.richMessage.isDefined) Rich
				else Plain

			val commonsMail: Email = format match {
				case Plain => new SimpleEmail().setMsg(mail.message)
				case Rich => new HtmlEmail().setHtmlMsg(mail.richMessage.get).setTextMsg(mail.message)
				case MultiPart => {
					val attachment = new EmailAttachment()
					attachment.setPath(mail.attachment.get.getAbsolutePath)
					attachment.setDisposition(EmailAttachment.ATTACHMENT)
					attachment.setName(mail.attachment.get.getName)
					new MultiPartEmail().attach(attachment).setMsg(mail.message)
				}
			}

			// TODO Set authentication from your configuration, sys properties or w/e

			// Can't add these via fluent API because it produces exceptions
			//       mail.to foreach (commonsMail.addTo(_))
			//       mail.cc foreach (commonsMail.addCc(_))
			//       mail.bcc foreach (commonsMail.addBcc(_))

			commonsMail.setHostName("localhost")
			commonsMail.setSmtpPort(25)
			commonsMail.setAuthenticator(new DefaultAuthenticator("noreply@trs.mail.net", "%hsi6658"))


			commonsMail.
				setFrom(mail.from._1, mail.from._2).setSubject(mail.subject).addTo(mail.to.mkString).send()
		}
	}

}