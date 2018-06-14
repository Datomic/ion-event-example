Sample Datomic Ion app that

* tracks AWS CodeDeploy events, storing information about each
  CodeDeploy event in Datomic.
* responds to a Slack event by querying recent CodeDeploy events and
  writing to a Slack channel.

The code is heavily commented:

* [REPL Walkthrough](https://github.com/Datomic/ion-event-example/blob/master/siderail/demo.clj)
* [Ion
  Source](https://github.com/Datomic/ion-event-example/blob/master/src/datomic/ion/event_example.clj)
  
* [Ion Configuration](https://github.com/Datomic/ion-event-example/blob/master/resources/datomic/ion-config.edn)
* [Project Configuration](https://github.com/Datomic/ion-event-example/blob/master/deps.edn)


