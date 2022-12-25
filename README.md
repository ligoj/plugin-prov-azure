# :link: Ligoj Azure plugin [![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-prov-azure/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.ligoj.plugin/plugin-prov-azure)

[![Build Status](https://app.travis-ci.com/github/ligoj/plugin-prov-azure.svg?branch=master)](https://app.travis-ci.com/github/ligoj/plugin-prov-azure)
[![Build Status](https://circleci.com/gh/ligoj/plugin-prov-azure.svg?style=svg)](https://circleci.com/gh/ligoj/plugin-prov-azure)
[![Build Status](https://ci.appveyor.com/api/projects/status/cuupbmv883r7ay9e/branch/master?svg=true)](https://ci.appveyor.com/project/ligoj/plugin-prov-azure/branch/master)
[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=org.ligoj.plugin%3Aplugin-prov-azure&metric=coverage)](https://sonarcloud.io/dashboard?id=org.ligoj.plugin%3Aplugin-prov-azure)
[![Quality Gate](https://sonarcloud.io/api/project_badges/measure?metric=alert_status&project=org.ligoj.plugin:plugin-prov-azure)](https://sonarcloud.io/dashboard/index/org.ligoj.plugin:plugin-prov-azure)
[![Codacy Badge](https://api.codacy.com/project/badge/Grade/8fcbd90fbb534a198e67756474b68218)](https://www.codacy.com/gh/ligoj/plugin-prov-azure?utm_source=github.com&amp;utm_medium=referral&amp;utm_content=ligoj/plugin-prov-azure&amp;utm_campaign=Badge_Grade)
[![CodeFactor](https://www.codefactor.io/repository/github/ligoj/plugin-prov-azure/badge)](https://www.codefactor.io/repository/github/ligoj/plugin-prov-azure)
[![License](http://img.shields.io/:license-mit-blue.svg)](http://fabdouglas.mit-license.org/)

[Ligoj](https://github.com/ligoj/ligoj) Azure provisioning plugin, and extending [Provisioning plugin](https://github.com/ligoj/plugin-prov)
Provides the following features :
- Prices are updated from the Azure API prices.
- Supported services : Compute (including software) with all terms, Storage and managed databases (no DTU and Hyperscale)

# Subscription parameters
* Tenant ID (Directory ID)
* Application ID (Id of application account of Ligoj)
* Key (secret token of application account of Ligoj)
* Subscription
* Resource group

## How to create/get these Azure parameters?
### Tenant ID/Application ID and Key
Everything takes place in [Azure Active Directory](https://portal.azure.com/?l=en.en-us#blade/Microsoft_AAD_IAM)
* Navigate to [RegisteredApps](https://portal.azure.com/?l=en.en-us#blade/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/RegisteredApps)
* Click on `New application registration`
* Fill the form : Name=`ligoj`, Application type=`Web app / API`, Sign-on URL=Ligoj URL, can be updated later
* `Create`
* `Create registration`
* Copy the `Application ID`
* Click on `Keys` (right panel)
* In the `Passwords` panel, fill `Key Description` and `Duration`, then `Save`
* Copy the one time displayed key value. 
* Navigate to [Properties](https://portal.azure.com/?l=en.en-us#blade/Microsoft_AAD_IAM/ActiveDirectoryMenuBlade/Properties)
* Copy the `Directory ID`, used as `Tenant ID` by Ligoj

### Resource Group
Navigate to [Resource groups](https://portal.azure.com/?l=en.en-us#blade/HubsExtension/Resources/resourceType/Microsoft.Resources%2Fsubscriptions%2FresourceGroups)
Copy the resource group name
Grant the rights to `ligoj` account on the selected resource group

### Subscription
* Navigate to [Cost Management + Billing](https://portal.azure.com/?l=en.en-us#blade/Microsoft_Azure_Billing/SubscriptionsBlade)
* Get the subscription id from one of your enabled subscription

# Technical details
Used API is `Microsoft.Compute` (2017-03-30)
Authentication is `OAuth2`, no required CLI to be installed
