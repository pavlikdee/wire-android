/**
  * Wire
  * Copyright (C) 2018 Wire Swiss GmbH
  *
  * This program is free software: you can redistribute it and/or modify
  * it under the terms of the GNU General Public License as published by
  * the Free Software Foundation, either version 3 of the License, or
  * (at your option) any later version.
  *
  * This program is distributed in the hope that it will be useful,
  * but WITHOUT ANY WARRANTY; without even the implied warranty of
  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  * GNU General Public License for more details.
  *
  * You should have received a copy of the GNU General Public License
  * along with this program.  If not, see <http://www.gnu.org/licenses/>.
  */
package com.waz.zclient.notifications.controllers

import java.util.concurrent.TimeUnit

import com.waz.api.IConversation
import com.waz.api.NotificationsHandler.NotificationType
import com.waz.api.impl.AccentColor
import com.waz.content._
import com.waz.model._
import com.waz.service.conversation.{ConversationsListStateService, ConversationsService, ConversationsUiService}
import com.waz.service.images.ImageLoader
import com.waz.service.push.NotificationService.NotificationInfo
import com.waz.service.push.{GlobalNotificationsService, NotificationService}
import com.waz.service.{AccountsService, UiLifeCycle, UserService}
import com.waz.specs.AndroidFreeSpec
import com.waz.utils.events.{Signal, SourceSignal}
import com.waz.utils.returning
import com.waz.zclient.WireApplication.{AccountToAssetsStorage, AccountToImageLoader}
import com.waz.zclient.common.controllers.SoundController
import com.waz.zclient.common.controllers.global.AccentColorController
import com.waz.zclient.controllers.navigation.INavigationController
import com.waz.zclient.conversation.ConversationController
import com.waz.zclient.core.stores.conversation.ConversationChangeRequester
import com.waz.zclient.messages.controllers.NavigationController
import com.waz.zclient.utils.ResString
import com.waz.zclient.utils.ResString.{AnyRefArgs, StringArgs}
import com.waz.zclient.{Module, WireContext}
import org.junit.Test
import org.scalatest.Suite
import org.threeten.bp.Instant

import scala.collection.immutable.SortedSet
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}

class MessageNotificationsControllerTest extends AndroidFreeSpec { this: Suite =>
  import com.waz.ZLog.ImplicitTag.implicitLogTag

  implicit val context: WireContext = null

  implicit val timeout = Duration(5000, TimeUnit.MILLISECONDS)

  val userData = UserData("TestUser")
  val userId = userData.id
  val convId = ConvId(userId.str)
  val convData = ConversationData(convId, RConvId(), None, userId, IConversation.Type.ONE_TO_ONE)

  val displayedNots = new SourceSignal[Map[UserId, Seq[NotId]]]()

  val globalNotifications = new GlobalNotificationsService {
    override val groupedNotifications = Signal(Map.empty[UserId, (Boolean, Seq[NotificationService.NotificationInfo])])

    override def markAsDisplayed(userId: UserId, nots: Seq[NotId]): Future[Any] =
      Future.successful(displayedNots.mutate { cur =>
        cur + (userId -> (cur.getOrElse(userId, Seq.empty).toSet ++ nots).toSeq)
      })
  }

  private val notsInManager = new SourceSignal[Map[Int, NotificationProps]]()

  class NewModule extends Module {

    private val notificationManager = new NotificationManagerWrapper {

      override def getActiveNotificationIds: Seq[Int] = {
        notsInManager.currentValue.getOrElse(Map.empty).keys.toSeq
      }

    }

    private val uiLifeCycle = returning(mock[UiLifeCycle]) { uiLifeCycle =>
      (uiLifeCycle.uiActive _).expects().anyNumberOfTimes().returning(Signal.const(true))
    }

    private val usersStorage = returning(mock[UsersStorage]) { usersStorage =>
      (usersStorage.signal _).expects(userId).anyNumberOfTimes().returning(Signal.const(userData))
      (usersStorage.optSignal _).expects(userId).anyNumberOfTimes().returning(Signal.const(Some(userData)))
    }

    private val convStorage = returning(mock[ConversationStorage]) { convStorage =>
      (convStorage.convsSignal _).expects().anyNumberOfTimes().returning(Signal(ConversationsSet(SortedSet(convData))))
      (convStorage.optSignal _).expects(convId).anyNumberOfTimes().returning(Signal.const(Option(convData)))
      (convStorage.conversations _).expects().anyNumberOfTimes().returning(IndexedSeq(convData))
    }

    private val convsStats = returning(mock[ConversationsListStateService]) { convsStats =>
      (convsStats.selectedConversationId _).expects().anyNumberOfTimes().returning(Signal.const(Some(convId)))
      (convsStats.selectConversation _).expects(Some(convId)).anyNumberOfTimes().returning(Future.successful(()))
    }

    private val conversations = returning(mock[ConversationsService]) { conversations =>
      (conversations.forceNameUpdate _).expects(convId).anyNumberOfTimes().returning(Future.successful(Option((convData, convData))))
      (conversations.isGroupConversation _).expects(convId).anyNumberOfTimes().returning(Future.successful(false))
    }

    private val imageLoader = mock[ImageLoader]

    private val assetsStorage = mock[AssetsStorage]
    (assetsStorage.get _).expects(*).anyNumberOfTimes().returning(Future.successful(Option.empty[AssetData]))

    private val iNavController = returning(mock[INavigationController]) { iNavController =>
      (iNavController.addNavigationControllerObserver _).expects(*).anyNumberOfTimes()
    }

    private val soundController = returning(mock[SoundController]) { ctrl =>
      (ctrl.isVibrationEnabled _).expects().anyNumberOfTimes().returning(false)
      (ctrl.soundIntensityFull _).expects().anyNumberOfTimes().returning(false)
      (ctrl.soundIntensityNone _).expects().anyNumberOfTimes().returning(true)
    }

    // `MessageNotificationController` receives `NotificationInfo`s from here
    bind[GlobalNotificationsService] to globalNotifications
    // processed notifications end up here
    bind[NotificationManagerWrapper] to notificationManager

    // mocked global entities
    bind[TeamsStorage]    to mock[TeamsStorage]
    bind[AccountsService] to accounts
    bind[UiLifeCycle]     to uiLifeCycle

    // mocked services of the current ZMessaging
    bind[Signal[UserId]]                        to Signal.const(userId)
    bind[Signal[Option[UserId]]]                to Signal.const(Some(userId))
    bind[Signal[AccentColor]]                   to Signal.const(AccentColor(0, 0, 0, 0))
    bind[Signal[UsersStorage]]                  to Signal.const(usersStorage)
    bind[Signal[ConversationStorage]]           to Signal.const(convStorage)
    bind[Signal[ConversationsListStateService]] to Signal.const(convsStats)
    bind[Signal[ConversationsUiService]]        to Signal.const(mock[ConversationsUiService])
    bind[Signal[ConversationsService]]          to Signal.const(conversations)
    bind[Signal[MembersStorage]]                to Signal.const(mock[MembersStorage])
    bind[Signal[UserService]]                   to Signal.const(mock[UserService])
    bind[Signal[OtrClientsStorage]]             to Signal.const(mock[OtrClientsStorage])
    bind[Signal[AssetsStorage]]                 to Signal.const(mock[AssetsStorage])
    bind[Signal[ImageLoader]]                   to Signal.const(imageLoader)

    bind[Signal[AccountsService]] to Signal.const(accounts)
    (accounts.accountsWithManagers _).expects().anyNumberOfTimes().returning(Signal.const(Set(userId)))

    bind [AccountToImageLoader]   to (_ => Future.successful(Option(imageLoader)))
    bind [AccountToAssetsStorage] to (_ => Future.successful(Option(assetsStorage)))

    // mocked controllers
    bind[AccentColorController]  to new AccentColorController()
    bind[NavigationController]   to new NavigationController()
    bind[INavigationController]  to iNavController

    private val convController = new ConversationController()
    convController.selectConv(Some(convId), ConversationChangeRequester.START_CONVERSATION)
    bind[ConversationController] to convController
    bind[SoundController]        to soundController
  }

  private def createInfo(text: String = "111",
                        convId: ConvId = ConvId(),
                        userName: String = "user1",
                        convName: String = "user1",
                        isEphemeral: Boolean = false,
                        notificationType: NotificationType = NotificationType.TEXT
                       ) =
    NotificationInfo(
      NotId(Uid().str), notificationType, Instant.now(), text, convId, Some(userName),
      Some(convName), userPicture = None, isEphemeral = isEphemeral, hasBeenDisplayed = false
    )


  private def sendInfos(ns: Seq[NotificationInfo], userId: UserId = userId): Unit =
    globalNotifications.groupedNotifications ! Map(userId -> (true, ns))

  private def sendInfo(info: NotificationInfo, userId: UserId = userId): Unit =
    globalNotifications.groupedNotifications ! Map(userId -> (true, Seq(info)))

  private def waitForProps(filter: Map[Int, NotificationProps] => Boolean) =
    Await.result(notsInManager.filter(filter).head, timeout)

  private def waitForOne(filter: NotificationProps => Boolean = _ => true) =
    waitForProps(map => { map.size == 1 && filter(map.head._2) }).head._2

  private var controller: MessageNotificationsController = _

  // TODO: check why 'beforeAll' is not called automatically
  private def setup(bundleEnabled: Boolean = false): Unit = {
    this.beforeAll()
    implicit val module: NewModule = new NewModule

    notsInManager ! Map.empty
    waitForProps(_.isEmpty)

    controller = new MessageNotificationsController(bundleEnabled = bundleEnabled, applicationId = "")

    controller.notificationToCancel.onUi { id =>
      notsInManager ! notsInManager.currentValue.getOrElse(Map.empty).filterKeys(_ != id)
    }

    controller.notificationToBuild.onUi { case (id, props) =>
      notsInManager ! (notsInManager.currentValue.getOrElse(Map.empty) + (id -> props))
    }
  }

  @Test
  def displayNotificationForReceivedLike(): Unit = {
    setup()

    sendInfo(NotificationInfo(NotId(Uid().str), NotificationType.LIKE, Instant.now, "", convId) )

    waitForOne()
  }

  @Test
  def receiveConnectRequest(): Unit = {
    setup()

    sendInfo(createInfo(text = "", convId = convId, userName = userData.name, convName = userData.name, notificationType = NotificationType.CONNECT_REQUEST))

    waitForOne()
  }

  @Test
  def receiveTextNotification(): Unit = {
    setup(bundleEnabled = true)

    sendInfo(createInfo("1:1"))

    waitForOne()
  }

  @Test
  def receiveTwoTextsFromOneUser(): Unit = {
    setup()

    val convId = ConvId()

    sendInfos(List(createInfo("1:1", convId = convId), createInfo("1:2", convId = convId)))

    waitForOne()
  }

  @Test
  def receiveTwoTextsFromTwoUsers(): Unit = {
    setup()

    val convId1 = ConvId()
    val convId2 = ConvId()

    sendInfos(List(createInfo("one", convId = convId1), createInfo("two", convId = convId2)))

    waitForOne { props =>
      props.contentTitle.exists(_.header.args == AnyRefArgs(List("2"))) && // the title says there are two conversations
      props.contentText.exists(_.body == ResString(0, 0, StringArgs(List("two")))) // the main message is the last one
    }

  }

  @Test
  def receiveNotificationsFromManyUsers(): Unit = {
    setup()

    val user1 = (UserId(), "user1")
    val user2 = (UserId(), "user2")
    val user3 = (UserId(), "user3")

    val ns = List(
      (user1, "1:1"),
      (user2, "2:2"),
      (user3, "3:3"),
      (user1, "1:4"),
      (user3, "3:5"),
      (user2, "2:6"),
      (user3, "3:7"),
      (user1, "1:8"),
      (user3, "3:9")
    )

    val infos = ns.map { n =>
      createInfo(text = n._2, convId = ConvId(n._1._1.str), userName = n._1._2, convName = n._1._2)
    }

    sendInfos(infos)

    waitForOne { props =>
      props.contentTitle.exists(_.header.args == AnyRefArgs(List("3"))) && // three conversations
      props.contentText.exists(_.body == ResString(0, 0, StringArgs(List("3:9")))) // the main message is the last one
    }
  }

  @Test
  def receiveTwoTextsFromOneUserInABundle(): Unit = {
    setup(bundleEnabled = true)

    val convId = ConvId()

    sendInfos(List(createInfo(text = "1:1", convId = convId), createInfo(text = "1:2", convId = convId)))

    waitForOne { props => // the summary comes first
      props.groupSummary.contains(true) &&
      props.group.contains(userId)
    }

    waitForProps { nots => // the summary is followed by a notification with the same group id (= userId)
      nots.size == 2 &&
      nots.tail.head._2.group.contains(userId)
    }
  }

  @Test
  def receiveEphemeralMessageInOneConv(): Unit = {
    setup()

    val convId = ConvId()

    val info1 = createInfo(text = "111", convId = convId)
    val info2 = createInfo(text = "222", convId = convId)
    val info3 = createInfo(text = "333", convId = convId, isEphemeral = true)

    sendInfos(List(info1, info2, info3))

    waitForOne { props =>
      props.contentTitle.exists(_.header.args == StringArgs(Nil)) && // the title doesn't contain the username
      props.style.exists(_.lines.map(_.body.args.asInstanceOf[StringArgs].args.headOption) == List(Some("111"), Some("222"), None)) // the body contains two first messages, but only a placeholder for the third one
    }
  }

  @Test
  def receiveEphemeralMessageInTwoConvs(): Unit = {
    setup()

    val info1 = createInfo(text = "111", convId = ConvId(), userName = "user1 conv name", convName = "user1 user name")
    val info2 = createInfo(text = "222", convId = ConvId(), userName = "user2", convName = "user2", isEphemeral = true)

    sendInfos(List(info1, info2))

    waitForOne { props =>
      props.style.exists(_.lines.size == 2) && // we receive two one-liners
      props.style.exists(_.lines.head.header.args == StringArgs(List("user1 user name", "user1 conv name"))) && // the first contains the user name and the conv name
      props.style.exists(_.lines.head.body.args ==  StringArgs(List("111"))) && // and the message
      props.style.exists(_.lines.tail.head.header.args == StringArgs(Nil)) && // the other does not have them - instead strings from the resources should be displayed
      props.style.exists(_.lines.tail.head.body.args == StringArgs(Nil))
    }
  }

  @Test
  def receiveManyEphemeralsAsABundle(): Unit = {
    setup(bundleEnabled = true)

    val convId = ConvId()
    val userName = "user1"

    val info1 = createInfo(text = "111", convId = convId, userName = userName, convName = userName, isEphemeral = true)
    val info2 = createInfo(text = "222", convId = convId, userName = userName, convName = userName, isEphemeral = true)
    val info3 = createInfo(text = "333", convId = convId, userName = userName, convName = userName, isEphemeral = true)

    sendInfos(List(info1, info2, info3))

    waitForProps(_.size == 2) // only the summary and one notification, not three
  }

  @Test
  def receiveEphemeralAsFirst(): Unit = {
    setup()

    val convId = ConvId()

    val info1 = createInfo(text = "111", convId = convId, isEphemeral = true)
    val info2 = createInfo(text = "222", convId = convId, isEphemeral = false)

    sendInfos(List(info1, info2))

    waitForOne { props =>
      props.contentTitle.exists(_.header.args == StringArgs(Nil)) && // the title doesn't contain the username
      props.contentText.exists(_.body.args == StringArgs(List("222"))) // the body contains the second message
    }
  }

  @Test
  def receiveEphemeralAsFirstInTwoConvs(): Unit = {
    setup()

    val convId1 = ConvId()
    val userName1 = "user1"
    val convId2 = ConvId()
    val userName2 = "user2"


    val info1 = createInfo(text = "111", convId = convId1, userName = userName1, convName = userName1, isEphemeral = true)
    val info2 = createInfo(text = "222", convId = convId1, userName = userName1, convName = userName1, isEphemeral = false)
    val info3 = createInfo(text = "333", convId = convId2, userName = userName2, convName = userName2, isEphemeral = false)

    sendInfos(List(info1, info2, info3))

    waitForOne { props =>
      props.contentTitle.exists(_.header.args == AnyRefArgs(List("2"))) && // the title says that these are notifications from two conversations
      props.style.exists(_.lines.size == 3) && // there are three notifications
      props.style.exists(_.lines.head.header.args == StringArgs(Nil)) && // but no information visible about the first one
      props.style.exists(_.lines.tail.head.header.args == StringArgs(List(userName1, userName1))) && // the other two are visible
      props.style.exists(_.lines.tail.tail.head.header.args == StringArgs(List(userName2, userName2))) &&
      props.style.exists(_.lines.map(_.body.args.asInstanceOf[StringArgs].args.headOption) == List(None, Some("222"), Some("333")))
    }
  }
}
