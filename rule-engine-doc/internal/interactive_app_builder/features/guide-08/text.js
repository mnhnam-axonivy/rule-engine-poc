/**
 * Guide 08 — texts of the walkthrough. tour.chapter.<id> are the headings of the tutorial
 * page, word for word.
 */
import { ABOUT_RULES } from '../shared/project.js';

export const strings = {
  en: {
    'lang': 'en',
    'tour.intro.title': 'When the runtime cannot answer',
    'tour.intro.text': 'Watch the Business Rule Task throw its BPMN errors: the container is stopped, a model is missing, the runtime is too slow. The Error Boundary Event decides how the process goes on.',
    'tour.intro.duration': 'about 3 min',
    'tour.chapter.unreachable': 'The runtime is not reachable',
    'tour.chapter.catch': 'Catch the error',
    'tour.chapter.missing': 'A model the runtime does not have',
    'tour.chapter.timeout': 'Timeout',

    'tour.running.title': 'The decision runtime',
    'tour.running.text': 'The container listens on `http://localhost:8090`. This switch stands in for `docker start` and `docker stop`.',
    'tour.stopped.title': 'Stopped',
    'tour.stopped.text': 'The container is down now, as after `docker stop axonivy-rule-engine`.',
    'tour.cut.title': 'Nothing answers',
    'tour.cut.text': 'The engine and the process are fine. The container is down, so a call of the connector reaches nothing.',
    'tour.thrown.title': 'A BPMN error',
    'tour.thrown.text': 'The element cannot connect. It does not fail with a stack trace: it throws an error with a code that a process can react to.',
    'tour.caught.title': 'Error Boundary Event',
    'tour.caught.text': 'The event on the Business Rule Task has the error code `rule:engine`. It catches the error, and the process continues from here.',
    'tour.otherPath.title': 'Another path',
    'tour.otherPath.text': 'The process ends at “not priced” instead of stopping, and the page of the demo shows the error. What this path does is up to you: a default value, a task for a person, a retry later.',
    'tour.logged.title': 'What the event did',
    'tour.logged.text': 'Its output code stored the error code and the message in the process data and wrote this line.',

    'tour.code.title': 'Error Code',
    'tour.code.text': 'Error codes are hierarchical. `rule:engine` catches every error of the Business Rule Task: unreachable, timeout, dmn and mapping.',
    'tour.output.title': 'Output',
    'tour.output.text': '`error` is the error that was caught. Its code and message go into the process data, where the rest of the process can use them.',
    'tour.narrow.title': 'A narrower code',
    'tour.narrow.text': 'Now the event only catches `rule:engine:timeout`. Use this to treat one error differently from the others.',
    'tour.unhandled.title': 'Unhandled',
    'tour.unhandled.text': 'The container is stopped again, so the error is `rule:engine:unreachable`. No event fits it: the process stops at the Business Rule Task.',
    'tour.unhandledLog.title': 'Unhandled error',
    'tour.unhandledLog.text': 'The log names the error. In a real application the case would now be in the error state, waiting for an administrator.',

    'tour.autoDeploy.title': 'AutoDeploy',
    'tour.autoDeploy.text': 'With `true` a project brings its models along: the element registers the `.dmn` file before it evaluates it.',
    'tour.off.title': 'Switched off',
    'tour.off.text': 'Now the project sends nothing. The models have to reach the runtime another way, through its API or a Rules UI.',
    'tour.notLoaded.title': 'The runtime refuses',
    'tour.notLoaded.text': 'The runtime is up, but empty. It answers `MODEL_NOT_FOUND`, and the element throws `rule:engine:dmn`: the call got through, the answer is an error.',
    'tour.refused.title': 'Caught as well',
    'tour.refused.text': '`rule:engine` also catches `rule:engine:dmn`. Retrying would not help here, though: the model has to be loaded first.',

    'tour.timeoutVar.title': 'RuleEngine.TimeoutSeconds',
    'tour.timeoutVar.text': 'How long a Business Rule Task waits for the result, 10 seconds by default. It holds for every Business Rule Task of the application.',
    'tour.timeoutSet.title': 'Two seconds',
    'tour.timeoutSet.text': 'Every Business Rule Task now waits two seconds at most.',
    'tour.slow.title': 'A slow runtime',
    'tour.slow.text': 'The container is up, but answers too late, as it may under heavy load or right after a start.',
    'tour.timedOut.title': 'Timeout',
    'tour.timedOut.text': 'After two seconds the element gives up and throws `rule:engine:timeout`. A decision normally takes about a millisecond.',
    'tour.sameEvent.title': 'One event for all of them',
    'tour.sameEvent.text': 'The same Error Boundary Event caught this error too. One event with `rule:engine` is the least every Business Rule Task should have.',

    'about.rules': ABOUT_RULES
  }
};
