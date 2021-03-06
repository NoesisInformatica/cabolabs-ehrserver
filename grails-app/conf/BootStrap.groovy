import com.cabolabs.ehrserver.openehr.demographic.Person
import com.cabolabs.ehrserver.openehr.common.generic.PatientProxy
import grails.util.Holders

import com.cabolabs.security.RequestMap
import com.cabolabs.security.User
import com.cabolabs.security.Role
import com.cabolabs.security.UserRole
import com.cabolabs.security.Organization
import com.cabolabs.ehrserver.query.*
import com.cabolabs.ehrserver.ehr.clinical_documents.*
import com.cabolabs.ehrserver.openehr.common.change_control.*
import com.cabolabs.ehrserver.openehr.common.generic.*
import grails.plugin.springsecurity.SecurityFilterPosition
import grails.plugin.springsecurity.SpringSecurityUtils

import com.cabolabs.ehrserver.identification.PersonIdType
import com.cabolabs.ehrserver.openehr.ehr.Ehr
import com.cabolabs.openehr.opt.manager.OptManager // load opts
import com.cabolabs.ehrserver.api.structures.PaginatedResults

import com.cabolabs.ehrserver.account.*

import grails.converters.*
import groovy.xml.MarkupBuilder
import org.codehaus.groovy.grails.web.converters.marshaller.NameAwareMarshaller

class BootStrap {

   private static String PS = System.getProperty("file.separator")
   
   def mailService
   def grailsApplication
   
   def init = { servletContext ->
      
      // Define server timezone
      TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
      
      // Used by query builder, all return String
      String.metaClass.asSQLValue = { operand ->
        if (operand == 'contains') return "'%"+ delegate +"%'" // Contains is translated to LIKE, we need the %
        return "'"+ delegate +"'"
      }
      Double.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
      Integer.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
      Long.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
      Date.metaClass.asSQLValue = { operand ->
        def formatterDateDB = new java.text.SimpleDateFormat( Holders.config.app.l10n.db_datetime_format )
        return "'"+ formatterDateDB.format( delegate ) +"'" 
      }
      Boolean.metaClass.asSQLValue = { operand ->
        return delegate.toString()
      }
     
      // call String.randomNumeric(5)
      String.metaClass.static.randomNumeric = { digits ->
        def alphabet = ['0','1','2','3','4','5','6','7','8','9']
        new Random().with {
          (1..digits).collect { alphabet[ nextInt( alphabet.size() ) ] }.join()
        }
      }
     
      // --------------------------------------------------------------------
     
     // Marshallers
     JSON.registerObjectMarshaller(Date) {
        //println "JSON DATE MARSHAL"
        return it?.format(Holders.config.app.l10n.db_datetime_format)
     }
     
     // These for XML dont seem to work...
     XML.registerObjectMarshaller(Date) {
        //println "XML DATE MARSHAL"
        return it?.format(Holders.config.app.l10n.db_datetime_format)
     }
     
     JSON.registerObjectMarshaller(CompositionIndex) { composition ->
        return [uid: composition.uid,
                category: composition.category,
                startTime: composition.startTime,
                subjectId: composition.subjectId,
                ehrUid: composition.ehrUid,
                templateId: composition.templateId,
                archetypeId: composition.archetypeId,
                lastVersion: composition.lastVersion,
                organizationUid: composition.organizationUid,
                parent: composition.getParent().uid
               ]
     }
     
     XML.registerObjectMarshaller(CompositionIndex) { composition, xml ->
        xml.build {
          uid(composition.uid)
          category(composition.category)
          startTime(composition.startTime)
          subjectId(composition.subjectId)
          ehrUid(composition.ehrUid)
          templateId(composition.templateId)
          archetypeId(composition.archetypeId)
          lastVersion(composition.lastVersion)
          organizationUid(composition.organizationUid)
          parent(composition.getParent().uid)
        }
     }
     
     
     JSON.registerObjectMarshaller(DoctorProxy) { doctor ->
        return [namespace: doctor.namespace,
                type: doctor.type,
                value: doctor.value,
                name: doctor.name
               ]
     }
     
     JSON.registerObjectMarshaller(AuditDetails) { audit ->
        def a = [timeCommitted: audit.timeCommitted,
                 committer: audit.committer, // DoctorProxy
                 systemId: audit.systemId
                ]
        // audit for contributions have changeType null, so we avoid to add it here if it is null
        if (audit.changeType) a << [changeType: audit.changeType]
        return a
     }
     
     JSON.registerObjectMarshaller(Contribution) { contribution ->
        return [uid: contribution.uid,
                organizationUid: contribution.organizationUid,
                ehrUid: contribution.ehr.uid,
                versions: contribution.versions.uid, // list of uids
                audit: contribution.audit // AuditDetails
               ]
     }
     
     XML.registerObjectMarshaller(DoctorProxy) { doctor, xml ->
        xml.build {
          namespace(doctor.namespace)
          type(doctor.type)
          value(doctor.value)
          name(doctor.name)
        }
     }
     
     XML.registerObjectMarshaller(AuditDetails) { audit, xml ->
        xml.build {
          timeCommitted(audit.timeCommitted)
          committer(audit.committer) // DoctorProxy
          systemId(audit.systemId)
          if (audit.changeType) changeType(audit.changeType)
        }
     }
     
     XML.registerObjectMarshaller(Contribution) { contribution, xml ->
        xml.build {
          uid(contribution.uid)
          organizationUid(contribution.organizationUid)
          ehrUid(contribution.ehr.uid)
          /*
           * <versions>
           *  <string>8b68a18c-bcb1... </string>
           * </versions>
           */
          //versions(contribution.versions.uid) // list of uids
          /* doesnt work, see below!
          versions {
             contribution.versions.uid.each { _vuid ->
                uid(_vuid)
             }
          }
          */
          audit(contribution.audit) // AuditDetails
        }
        
        // works!
        // https://jwicz.wordpress.com/2011/07/11/grails-custom-xml-marshaller/
        // http://docs.grails.org/2.5.3/api/grails/converters/XML.html
         /*
          * <versions>
          *  <uid>8b68a18c-bcb1... </uid>
          * </versions>
          */
        xml.startNode 'versions'
        contribution.versions.uid.each { _vuid ->
           xml.startNode 'uid'
           xml.chars _vuid
           xml.end()
        }
        xml.end()
     }
     
     
     JSON.registerObjectMarshaller(Organization) { o ->
        return [uid: o.uid,
                name: o.name
               ]
     }
     
     XML.registerObjectMarshaller(Organization) { o, xml ->
        xml.build {
          uid(o.uid)
          name(o.name)
        }
     }
     
     JSON.registerObjectMarshaller(User) { u ->
        return [username: u.username,
                email: u.email,
                organizations: u.organizations
               ]
     }
     
     XML.registerObjectMarshaller(User) { u, xml ->
        xml.build {
          username(u.username)
          email(u.email)
          xml.startNode 'organizations'
          
             xml.convertAnother u.organizations
   
          xml.end()
        }
     }
     
     
     JSON.registerObjectMarshaller(Query) { q ->
        def j = [uid: q.uid,
                 name: q.name,
                 format: q.format,
                 type: q.type,
                 author: q.author
                ]
        
        if (q.type == 'composition')
        {
           j << [criteriaLogic: q.criteriaLogic]
           j << [templateId:    q.templateId]
           j << [criteria:      q.where.collect { [archetypeId: it.archetypeId, path: it.path, conditions: it.getCriteriaMap()] }]
        }
        else
        {
           j << [group:         q.group] // Group is only for datavalue
           j << [projections:   q.select.collect { [archetypeId: it.archetypeId, path: it.path] }]
        }
        
        return j
     }
     
     XML.registerObjectMarshaller(Query) { q, xml ->
        xml.build {
          uid(q.uid)
          name(q.name)
          format(q.format)
          type(q.type)
          author(q.author)
        }
        
        if (q.type == 'composition')
        {
           xml.startNode 'criteriaLogic'
              xml.chars (q.criteriaLogic ?: '')
           xml.end()
           xml.startNode 'templateId'
              xml.chars (q.templateId ?: '') // fails if null!
           xml.end()
           
           def criteriaMap
           def _value
           //q.where.each { criteria -> // with this the criteria clases are marshalled twice, it seems the each is returning the criteria instead of just processing the xml format creation.
           for (criteria in q.where) // works ok, so we need to avoid .each
           {
              criteriaMap = criteria.getCriteriaMap() // [attr: [operand: value]] value can be a list
              
              xml.startNode 'criteria'
                 xml.startNode 'archetypeId'
                    xml.chars criteria.archetypeId
                 xml.end()
                 xml.startNode 'path'
                    xml.chars criteria.path
                 xml.end()
                 xml.startNode 'conditions'
 
                    criteriaMap.each { attr, cond ->
                    
                       _value = cond.find{true}.value // can be a list, string, boolean, ...
                       
                       xml.startNode "$attr"
                          xml.startNode 'operand'
                             xml.chars cond.find{true}.key
                          xml.end()
                          
                          if (_value instanceof List)
                          {
                             xml.startNode 'list'
                                _value.each { val ->
                                   
                                   if (val instanceof Date)
                                   {
                                      // FIXME: should use the XML date marshaller
                                      xml.startNode 'item'
                                         xml.chars val.format(Holders.config.app.l10n.ext_datetime_utcformat_nof, TimeZone.getTimeZone("UTC"))
                                      xml.end()
                                   }
                                   else
                                   {
                                      xml.startNode 'item'
                                         xml.chars val.toString() // chars fails if type is Double or other non string
                                      xml.end()
                                   }
                                }
                             xml.end()
                          }
                          else
                          {
                             xml.startNode 'value'
                                xml.chars _value.toString() // chars fails if type is Double or other non string
                             xml.end()
                          }
                       xml.end()
                    }
                 xml.end()
              xml.end()
           }
        }
        else
        {
           xml.startNode 'group'
              xml.chars q.group
           xml.end()
           
           q.select.each { proj ->
              xml.startNode 'projection'
                xml.startNode 'archetypeId'
                  xml.chars proj.archetypeId
                xml.end()
                xml.startNode 'path'
                  xml.chars proj.path
                xml.end()
              xml.end()
           }
        }
     }
     

     JSON.registerObjectMarshaller(Ehr) { ehr ->
        return [uid: ehr.uid,
                dateCreated: ehr.dateCreated,
                subjectUid: ehr.subject.value,
                systemId: ehr.systemId,
                organizationUid: ehr.organizationUid
               ]
     }
     
     XML.registerObjectMarshaller(Ehr) { ehr, xml ->
        xml.build {
          uid(ehr.uid)
          dateCreated(ehr.dateCreated)
          subjectUid(ehr.subject.value)
          systemId(ehr.systemId)
          organizationUid(ehr.organizationUid)
        }
     }
     
     
     JSON.registerObjectMarshaller(Person) { person ->
        return [uid: person.uid,
                firstName: person.firstName,
                lastName: person.lastName,
                dob: person.dob,
                sex: person.sex,
                idCode: person.idCode,
                idType: person.idType,
                organizationUid: person.organizationUid
               ]
     }
     
     XML.registerObjectMarshaller(Person) { person, xml ->
        xml.build {
          uid(person.uid)
          firstName(person.firstName)
          lastName(person.lastName)
          dob(person.dob)
          sex(person.sex)
          idCode(person.idCode)
          idType(person.idType)
          organizationUid(person.organizationUid)
        }
     }
     
     /*
     XML.registerObjectMarshaller(new NameAwareMarshaller() {
        @Override
        public boolean supports(java.lang.Object object) {
           return (object instanceof PaginatedResults)
        }

        @Override
        String getElementName(java.lang.Object o) {
           'result'
        }
     })
     */
     
     JSON.registerObjectMarshaller(PaginatedResults) { pres ->
        
        pres.update() // updates and checks pagination values
        
        return [
           "${pres.listName}": pres.list,
           pagination: [
              'max': pres.max,
              'offset': pres.offset,
              nextOffset: pres.nextOffset, // TODO: verificar que si la cantidad actual es menor que max, el nextoffset debe ser igual al offset
              prevOffset: pres.prevOffset
           ]
        ]
     }
     
     XML.registerObjectMarshaller(PaginatedResults) { pres, xml ->
        
        pres.update() // updates and checks pagination values
        
        // Our list marshaller to customize the name
        xml.startNode pres.listName
           
           xml.convertAnother pres.list // this works, generates "person" nodes
        
           /* doesnt generate the patient root, trying with ^
           pres.list.each { item ->
              xml.convertAnother item // marshaller fot the item type should be declared
           }
           */

        xml.end()
        
        xml.startNode 'pagination'
           xml.startNode 'max'
           xml.chars pres.max.toString() // integer fails for .chars
           xml.end()
           xml.startNode 'offset'
           xml.chars pres.offset.toString()
           xml.end()
           xml.startNode 'nextOffset'
           xml.chars pres.nextOffset.toString()
           xml.end()
           xml.startNode 'prevOffset'
           xml.chars pres.prevOffset.toString()
           xml.end()
        xml.end()
     }
     
     
     
     
     // Init id types
     if (PersonIdType.count() == 0)
     {
        def idtypes = [
           new PersonIdType(name:'DNI',  code:'DNI'),
           new PersonIdType(name:'CI',   code:'CI'),
           new PersonIdType(name:'Passport', code:'Passport'),
           new PersonIdType(name:'SSN',  code:'SSN'),
           new PersonIdType(name:'UUID', code:'UUID'),
           new PersonIdType(name:'OID',  code:'OID')
        ]
        idtypes.each {
           it.save(failOnError:true, flush:true)
        }
     }
     
     
     //****** SECURITY *******
     
     // Register custom auth filter
     // ref: https://objectpartners.com/2013/07/11/custom-authentication-with-the-grails-spring-security-core-plugin/
     // See 'authFilter' in grails-app/conf/spring/resources.groovy
     // ref: http://grails-plugins.github.io/grails-spring-security-core/guide/filters.html
     SpringSecurityUtils.clientRegisterFilter('authFilter', SecurityFilterPosition.SECURITY_CONTEXT_FILTER.order + 10)

     

     def organizations = []
     if (Organization.count() == 0)
     {
        // Sample organizations
        organizations << new Organization(name: 'Hospital de Clinicas', number: '1234')
        organizations << new Organization(name: 'Clinica del Tratamiento del Dolor', number: '6666')
        organizations << new Organization(name: 'Cirugia Estetica', number: '5555')
        
        organizations.each {
           it.save(failOnError:true, flush:true)
        }
     }
     else organizations = Organization.list()
     
     if (RequestMap.count() == 0)
     {
        for (String url in [
         '/', // redirects to login, see UrlMappings
         '/error', '/index', '/index.gsp', '/**/favicon.ico', '/shutdown',
         '/assets/**', '/**/js/**', '/**/css/**', '/**/images/**', '/**/fonts/**',
         '/login', '/login.*', '/login/*',
         '/logout', '/logout.*', '/logout/*',
         '/user/register', '/user/resetPassword', '/user/forgotPassword',
         '/simpleCaptcha/**',
         '/j_spring_security_logout',
         '/rest/**',
         '/ehr/showCompositionUI', // will be added as a rest service via url mapping
         '/user/profile',
         
         // access for all roles to let users access their own profile
         '/user/show/**',
         '/user/edit/**',
         '/user/update/**'
        ])
        {
            new RequestMap(url: url, configAttribute: 'permitAll').save()
        }
       
        // sections
        // works for /app
        //new RequestMap(url: '/app/**', configAttribute: 'ROLE_ADMIN').save()
        
        new RequestMap(url: '/app/index', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER,ROLE_ORG_STAFF').save()
        new RequestMap(url: '/person/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER,ROLE_ORG_STAFF').save()
        new RequestMap(url: '/ehr/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER,ROLE_ORG_STAFF').save()
        new RequestMap(url: '/versionedComposition/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        new RequestMap(url: '/contribution/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER,ROLE_ORG_STAFF').save()
        new RequestMap(url: '/folder/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER,ROLE_ORG_STAFF').save()
        new RequestMap(url: '/query/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER,ROLE_ORG_STAFF').save()
        new RequestMap(url: '/operationalTemplateIndexItem/**', configAttribute: 'ROLE_ADMIN').save()
        new RequestMap(url: '/archetypeIndexItem/**', configAttribute: 'ROLE_ADMIN').save()
        new RequestMap(url: '/compositionIndex/**', configAttribute: 'ROLE_ADMIN').save()
        new RequestMap(url: '/operationalTemplate/**', configAttribute: 'ROLE_ADMIN').save()
        
        // the rest of the operations should be open and security is checked inside the action
        new RequestMap(url: '/user/index', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        new RequestMap(url: '/user/create', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        new RequestMap(url: '/user/save', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        new RequestMap(url: '/user/delete', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        new RequestMap(url: '/user/resetPasswordRequest/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        
        new RequestMap(url: '/role/**', configAttribute: 'ROLE_ADMIN').save()
        new RequestMap(url: '/organization/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()
        new RequestMap(url: '/personIdType/**', configAttribute: 'ROLE_ADMIN').save()
        
        new RequestMap(url: '/stats/**', configAttribute: 'ROLE_ADMIN,ROLE_ORG_MANAGER').save()

        new RequestMap(url: '/j_spring_security_switch_user', configAttribute: 'ROLE_SWITCH_USER,isFullyAuthenticated()').save()
     }
     if (Role.count() == 0 )
     {
        def adminRole = new Role(authority: 'ROLE_ADMIN').save(failOnError: true, flush: true)
        def orgManagerRole = new Role(authority: 'ROLE_ORG_MANAGER').save(failOnError: true, flush: true)
        def clinicalManagerRole = new Role(authority: 'ROLE_ORG_CLINICAL_MANAGER').save(failOnError: true, flush: true)
        def staffRole = new Role(authority: 'ROLE_ORG_STAFF').save(failOnError: true, flush: true)
        def userRole = new Role(authority: 'ROLE_USER').save(failOnError: true, flush: true)
     }
     if (User.count() == 0)
     {
        def adminUser = new User(username: 'admin', email: 'pablo.pazos@cabolabs.com',  password: 'admin')
        adminUser.organizations = [organizations[0], organizations[1]]
        adminUser.save(failOnError: true,  flush: true)
        
        def orgManUser = new User(username: 'orgman', email: 'pablo.swp+orgman@gmail.com',  password: 'orgman')
        orgManUser.organizations = [organizations[0], organizations[1]]
        orgManUser.save(failOnError: true,  flush: true)
        
        //UserRole.create( godlikeUser, (Role.findByAuthority('ROLE_ADMIN')), true )
        //UserRole.create( godlikeUser, (Role.findByAuthority('ROLE_ORG_MANAGER')), true )
        //UserRole.create( godlikeUser, (Role.findByAuthority('ROLE_ORG_STAFF')), true )
        //UserRole.create( godlikeUser, (Role.findByAuthority('ROLE_ORG_CLINICAL_MANAGER')), true )
        //UserRole.create( godlikeUser, (Role.findByAuthority('ROLE_USER')), true )
        
        UserRole.create( adminUser, (Role.findByAuthority('ROLE_ADMIN')), true )
        UserRole.create( orgManUser, (Role.findByAuthority('ROLE_ORG_MANAGER')), true )
     }

     //****** SECURITY *******
     
     
     log.debug( 'Current working dir: '+ new File(".").getAbsolutePath() ) // Current working directory
     
     
     // Always regenerate indexes in deploy
     def ti = new com.cabolabs.archetype.OperationalTemplateIndexer()
	  ti.indexAll()
     
     
     // OPT loading
     def optMan = OptManager.getInstance( Holders.config.app.opt_repo )
     optMan.loadAll()
     
     // Fake persons and roles
     def persons = []
     if (Person.count() == 0)
     {
        persons = [
           new Person(
               firstName: 'Pablo',
               lastName: 'Pazos',
               dob: new Date(81, 9, 24),
               sex: 'M',
               idCode: '4116238-0',
               idType: PersonIdType.get(1).code,
               role: 'pat',
               uid: '11111111-1111-1111-1111-111111111111'
           ),
           new Person(
               firstName: 'Barbara',
               lastName: 'Cardozo',
               dob: new Date(87, 2, 19),
               sex: 'F',
               idCode: '1234567-0',
               idType: PersonIdType.get(1).code,
               role: 'pat',
               uid: '22222222-1111-1111-1111-111111111111'
           ),
           new Person(
               firstName: 'Carlos',
               lastName: 'Cardozo',
               dob: new Date(80, 2, 20),
               sex: 'M',
               idCode: '3453455-0',
               idType: PersonIdType.get(1).code,
               role: 'pat',
               uid: '33333333-1111-1111-1111-111111111111'
           ),
           new Person(
               firstName: 'Mario',
               lastName: 'Gomez',
               dob: new Date(64, 8, 19),
               sex: 'M',
               idCode: '5677565-0',
               idType: PersonIdType.get(1).code,
               role: 'pat',
               uid: '44444444-1111-1111-1111-111111111111'
           ),
           new Person(
               firstName: 'Carla',
               lastName: 'Martinez',
               dob: new Date(92, 1, 5),
               sex: 'F',
               idCode: '84848884-0',
               idType: PersonIdType.get(1).code,
               role: 'pat',
               uid: '55555555-1111-1111-1111-111111111111'
           )
        ]
        
        def c = Organization.count()
        persons.eachWithIndex { p, i ->
           
           p.organizationUid = Organization.get(i % c + 1).uid
           
           if (!p.save())
           {
              println p.errors
           }
        }
     }
     if (Ehr.count() == 0)
     {
        // Fake EHRs for patients
        // Idem EhrController.createEhr
        def ehr
        
        persons.eachWithIndex { p, i ->
        
           if (p.role == 'pat')
           {
              ehr = new Ehr(
                 uid: p.uid, // the ehr id is the same as the patient just to simplify testing
                 subject: new PatientProxy(
                    value: p.uid
                 ),
                 organizationUid: p.organizationUid
              )
            
              if (!ehr.save()) println ehr.errors
           }
        }
     }
     
     def p1
     if (Plan.count() == 0)
     {
        // Create plans
        p1 = new Plan(
          name: "Free Educational",
          maxTransactions: 50,
          maxDocuments: 100,
          repositorySize: 1024*15*120, // allows 120 documents of 15KB
          totalRepositorySize: 1024*15*120*12, // monthly size * 12 months
          period: Plan.periods.MONTHLY
        )
        
        p1.save(failOnError: true)
     }
     else
     {
        p1 = Plan.get(1)
     }
     
     // Associate free plans by default
     def orgs = Organization.list()
     orgs.each { org ->
       if (!PlanAssociation.findByOrganizationUid(org.uid))
       {
          p1.associate( org )
       }
     }
   }
   
   def destroy = {
   }
}
