# Protocol Note: The Economic Game of Music & The 5Cs Regenerative Flywheel

This document defines the architectural implementation of a Music Industry "Digital Twin" on the Intergraph, modeled as a **5Cs Regenerative Flywheel**. It abstracts a music band and its ecosystem into an Infinite Economic Game where value flows through a regenerative cycle.

## 1. Core Thesis
A Band is not just a group of musicians but a **Field of Economic Games**. Value flows through a regenerative cycle: Content attracts Community, Community enables Commerce, Commerce empowers Coselling, and Campaigns amplify the entire loop.

**The Loop:** `Content` $\rightarrow$ `Community` $\rightarrow$ `Commerce` $\rightarrow$ `Coselling` $\rightarrow$ `Campaigns` $\rightarrow$ *(back to Content)*

## 2. Architectural Roles (The Players)

### The Campaign Orchestrator (GM Agent)
*   **Role**: The sovereign authority of the Flywheel.
*   **Objective**: Maximize **Campaign ROI** and **Network Density**.
*   **Authority**: Manages the **Table State** (the "Record") and configures the **Split Rules** for the 5Cs.

### The Creator (Band Agent)
*   **Role**: The "Content" Originator.
*   **Objective**: Grow **Reputation** and **Audience Reach**.
*   **Cognitive Sandbox**: Stores unreleased IP, private creative flow, and engagement signals.

### The Community Member (Fan Agent)
*   **Role**: The "Community" Participant and "Commerce" Customer.
*   **Objective**: Gain **Belonging**, **Access**, and **Status**.
*   **Cognitive Sandbox**: Stores personal collection, interaction history, and loyalty tier.

### The Coseller (Influencer/Partner Agent)
*   **Role**: The Distributed Sales Force.
*   **Objective**: Earn **Commission** and **Recurring Revenue Share**.
*   **Cognitive Sandbox**: Stores private analytics on their "Coseller Activation Rate" and attribution data.

## 3. The State Model (The Record)

### Table State (The Shared Ledger)
Shared among all authorized participants.
*   **The Content Catalog**: Metadata for songs, stories, and frameworks.
*   **The Community Graph**: Public density metrics and network effects.
*   **The Commerce Ledger**: Transparent tracking of transaction volume and AOV.
*   **The Coseller Registry**: Attribution rules and active partner lists.
*   **The Campaign Dashboard**: Live ROI metrics and viral coefficients.

## 4. The 5Cs Game Loop & Regenerative Mechanics

The "Game" is the continuous execution of this loop. Each stage feeds the next.

### Phase 1: Content $\rightarrow$ Community
*   **Move**: `{:action/type :content/publish}`
*   **Effect**: High-quality content attracts new Agent IDs (Fans) to register with the GM, expanding the **Community Graph**.

### Phase 2: Community $\rightarrow$ Commerce
*   **Move**: `{:action/type :commerce/purchase}`
*   **Effect**: Community trust lowers friction. Fans execute transactions. Value flows to the **Commerce Ledger**.

### Phase 3: Commerce $\rightarrow$ Coselling
*   **Move**: `{:action/type :coselling/activate}`
*   **Effect**: Satisfied Customers upgrade to **Coseller Agents**. Transaction data is used to target high-potential partners.

### Phase 4: Coselling $\rightarrow$ Campaigns
*   **Move**: `{:action/type :campaign/launch}`
*   **Effect**: The network of Cosellers amplifies a specific initiative (e.g., "Summer Tour Launch"). This drives **Viral Coefficient** and recruits new players.

### Phase 5: Campaigns $\rightarrow$ Content
*   **Move**: `{:action/type :content/generate-insight}`
*   **Effect**: Campaign data generates "Success Stories" and "Case Studies," which become new **Content**, restarting the loop.

## 5. Multi-Attribution & Economic Flows

Every transaction is a **Multi-Attribution Event** settled on the Intergraph Ledger.

### The "Splits" Logic
When a `commerce/purchase` occurs, the Ledger credits value across the flywheel based on influence:
1.  **Creator (Content)**: Base Revenue.
2.  **Community Curator**: Recognition for nurturing the space.
3.  **Coseller**: Commission for the direct sale.
4.  **Campaign Orchestrator**: Fee for coordinating the drive.

### Protocol Invariants
*   **Liveness**: Cosellers and Orchestrators must maintain a valid **Heartbeat**. If a Coseller goes dark, their attribution links pause, ensuring the active ecosystem is rewarded.
*   **Regeneration**: The goal is compounding value. A successful loop increases the **Reputation** of all participants, granting them higher influence (and potentially higher splits) in the next cycle.
