/*
* @GIT_BRANCH(string:repo branch, Required)
* @FORCE_REBUILD(bool:if force rebuild binary,default true,Optional)
*/

properties([
        parameters([
                string(
                        defaultValue: '',
                        name: 'GIT_BRANCH',
                        trim: true
                ),
                booleanParam(
                        defaultValue: true,
                        name: 'FORCE_REBUILD'
                )
        ]),
        pipelineTriggers([
            parameterizedCron('''
                H H(0-7)/4 * * * % GIT_BRANCH=release-4.0
                H H(0-7)/4 * * * % GIT_BRANCH=release-5.0
                H H(0-7)/4 * * * % GIT_BRANCH=release-5.1
                H H(0-7)/4 * * * % GIT_BRANCH=master
            ''')
        ])
])


def get_sha(repo) {
    sh "curl -s ${FILE_SERVER_URL}/download/builds/pingcap/ee/get_hash_from_github.py > gethash.py"
    return sh(returnStdout: true, script: "python gethash.py -repo=${repo} -version=${GIT_BRANCH} -s=${FILE_SERVER_URL}").trim()
}

RELEASE_TAG = "${GIT_BRANCH}-nightly"

def release_one(repo) {
    def sha1 =  get_sha(repo)
    def binary = "builds/pingcap/${repo}/test/${RELEASE_TAG}/${sha1}/linux-amd64/${repo}.tar.gz"
    def paramsBuild = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "EDITION", value: "community"),
        string(name: "OUTPUT_BINARY", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "GIT_HASH", value: sha1),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "TARGET_BRANCH", value: GIT_BRANCH),
        [$class: 'BooleanParameterValue', name: 'FORCE_REBUILD', value: FORCE_REBUILD],
    ]
    build job: "build-common",
            wait: true,
            parameters: paramsBuild

    def dockerfile = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/${repo}"
    def image = "hub.pingcap.net/guoyu/${repo}:${RELEASE_TAG}"
    if (GIT_BRANCH == "master") {
        image = "hub.pingcap.net/guoyu/${repo}:nightly"
    }
    def paramsDocker = [
        string(name: "ARCH", value: "amd64"),
        string(name: "OS", value: "linux"),
        string(name: "INPUT_BINARYS", value: binary),
        string(name: "REPO", value: repo),
        string(name: "PRODUCT", value: repo),
        string(name: "RELEASE_TAG", value: RELEASE_TAG),
        string(name: "DOCKERFILE", value: dockerfile),
        string(name: "RELEASE_DOCKER_IMAGES", value: FORCE_REBUILD),
    ]
    build job: "docker-common",
            wait: true,
            parameters: paramsDocker

    if (repo == "br") {
        def dockerfileLightning = "https://raw.githubusercontent.com/PingCAP-QE/ci/main/jenkins/Dockerfile/release/linux-amd64/lightning"
        def imageLightling = "hub.pingcap.net/guoyu/lightning:${RELEASE_TAG}"
        if (GIT_BRANCH == "master") {
        imageLightling = "hub.pingcap.net/guoyu/lightning:nightly"
    }
        def paramsDockerLightning = [
            string(name: "ARCH", value: "amd64"),
            string(name: "OS", value: "linux"),
            string(name: "INPUT_BINARYS", value: binary),
            string(name: "REPO", value: "lightning"),
            string(name: "PRODUCT", value: "lightning"),
            string(name: "RELEASE_TAG", value: RELEASE_TAG),
            string(name: "DOCKERFILE", value: dockerfileLightning),
            string(name: "RELEASE_DOCKER_IMAGES", value: imageLightling),
        ]
        build job: "docker-common",
                wait: true,
                parameters: paramsDockerLightning
        }
}

stage ("release") {
    node("${GO_BUILD_SLAVE}") {
        container("golang") {
            releaseRepos = ["dumpling","br","ticdc","tidb-binlog"]
            builds = [:]
            for (item in releaseRepos) {
                def product = "${item}"
                builds["build ${item}"] = {
                    release_one(product)
                }
            }
            parallel builds
        }
    }
}

