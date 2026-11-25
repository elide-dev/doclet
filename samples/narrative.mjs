// Add the authored module overview and guides to the model extracted from samples/java.
import { readFileSync, writeFileSync } from 'node:fs';

const [input, output] = process.argv.slice(2);
const model = JSON.parse(readFileSync(input, 'utf8'));
const text = value => ({ kind: 'text', text: value });
const link = (id, label) => ({ kind: 'link', target: { id }, code: true, children: [text(label)] });
const paragraph = (...children) => ({ kind: 'paragraph', children });
const heading = title => ({ kind: 'heading', level: 2, children: [text(title)] });
const code = value => ({ kind: 'code', language: 'java', text: value });
const plan = link('example.runtime.BuildPlan', 'BuildPlan');
const local = link('example.runtime.Planner$Local', 'Planner.Local');
const target = link('example.runtime.Target', 'Target');

model.module.doc = {
  summary: [text('Plan a Java application for the JVM or a native executable.')],
  body: [
    paragraph(text('Runtime Kit separates the configuration of an application from the tool that prepares it. An immutable '), plan, text(' holds the entry point and compiler arguments. A '), local, text(' validates the plan for the chosen '), target, text('.')),
    paragraph(text('Start with the preparation guide for a runnable example, or explore the package reference for constructors, extension points, and error handling.'))
  ]
};
model.module.guides = [{
  id: 'guide:prepare-a-build', slug: 'prepare-a-build', title: 'Prepare your first build',
  summary: [text('Create an immutable plan, select a runtime, and handle validation errors.')],
  body: [
    heading('Choose an entry point'),
    paragraph(text('Use '), link('example.runtime.BuildPlan#defaults(java.lang.String)', 'BuildPlan.defaults(String)'), text(' for a plan with no extra compiler flags. The entry point is the fully qualified class name.')),
    code('var plan = BuildPlan.defaults("example.Main");'),
    heading('Select the runtime'),
    paragraph(text('Construct a '), local, text(' for the JVM. For fine-grained progress reporting, pass '), link('example.runtime.Planner$Options', 'Planner.Options'), text(' to the overloaded constructor.')),
    code('var planner = new Planner.Local(Target.JVM);'),
    heading('Validate the plan'),
    paragraph(text('Preparation returns the validated plan. A blank entry point raises '), link('example.runtime.PlanException', 'PlanException'), text(' before execution.')),
    code('try {\n  var ready = planner.prepare(plan);\n  System.out.println(ready.mainClass());\n} catch (PlanException rejected) {\n  System.err.println(rejected.getMessage());\n}')
  ]
}, {
  id: 'guide:custom-step', slug: 'custom-step', title: 'Write a preparation step',
  summary: [text('Use the generic extension point to derive a launch command.')],
  body: [
    heading('Choose the output type'),
    paragraph(link('example.runtime.BuildStep', 'BuildStep<T>'), text(' describes the result of preparation. A step can return a command line, a manifest, or another build plan.')),
    code('BuildStep<List<String>> launch = p -> List.of("java", p.mainClass());'),
    heading('Apply the step'),
    paragraph(text('Pass a validated '), plan, text(' to the step. The result contains the command and its argument as separate entries.')),
    code('var command = launch.prepare(BuildPlan.defaults("example.Main"));')
  ]
}];
writeFileSync(output, JSON.stringify(model));
