import {before,after,beforeEach,test} from 'node:test';
import {readFile} from 'node:fs/promises';
import {initializeTestEnvironment,assertFails,assertSucceeds} from '@firebase/rules-unit-testing';
import {ref,set,remove} from 'firebase/database';
let env;
const db=()=>env.authenticatedContext('alice').database();
const reminder=()=>({moduleId:'missing_module',title:'Test reminder',dueAt:1792627200000,enabled:true,zoneId:'Asia/Manila'});
before(async()=>{env=await initializeTestEnvironment({projectId:'demo-studypath-reminder-targets',database:{host:'127.0.0.1',port:9000,rules:await readFile(new URL('../database.rules.json',import.meta.url),'utf8')}});});
after(async()=>{await env?.cleanup();});
beforeEach(async()=>{await env.clearDatabase();});
test('new reminders cannot point to a nonexistent module',async()=>{await assertFails(set(ref(db(),'users/alice/reminders/r1'),reminder()));});
test('a retained legacy reminder may be disabled or removed after its target disappears',async()=>{
 await env.withSecurityRulesDisabled(c=>set(ref(c.database(),'users/alice/reminders/r1'),reminder()));
 await assertSucceeds(set(ref(db(),'users/alice/reminders/r1'),{...reminder(),enabled:false}));
 await assertSucceeds(remove(ref(db(),'users/alice/reminders/r1')));
});
test('saved reading targets require the owning reading and an exact target union',async()=>{
 const value={schemaVersion:2,targetType:'SAVED_READING',readingId:'missing_reading',title:'Read again',dueAt:1792627200000,enabled:true,zoneId:'Asia/Manila'};
 await assertFails(set(ref(db(),'users/alice/reminders/r2'),value));
});
